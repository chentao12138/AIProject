package com.aistudy.server.source.asset.service;

import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.storage.StorageMetadata;
import com.aistudy.server.storage.StorageResult;
import com.aistudy.server.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aistudy.server.config.properties.UploadProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BUSINESS-004 — application service for SourceAsset (RAW file upload).
 *
 * <p>This is the ONLY caller of {@link SourceAssetMapper}. The RAW
 * bytes are preserved byte-for-byte through {@link StorageService}
 * (R-SOURCE-002 / NFR-DATA-001: nothing overwrites or re-encodes the
 * original file), and the metadata row records
 * originalName / mimeType / sizeBytes / sha256 / storageKey
 * (R-SOURCE-003).
 *
 * <h3>Upload flow (owner-scoped, two resources)</h3>
 *
 * <pre>
 *   1. parent source validation: SourceService.getMine(owner, spaceId, sourceId)
 *      — null → 404 (absent / not owned / cross-space collapse)
 *   2. file validation: empty → 400; over {@code aistudy.upload.max-file-size}
 *      → 413; unsupported extension or declared MIME → 415
 *   3. StorageService.store(stream) — server-generated key, SHA-256 +
 *      byte count computed on the SAME stream (never readAllBytes /
 *      MultipartFile.getBytes() on the production path)
 *   4. insert source_asset row
 *   5. DB transaction commit
 * </pre>
 *
 * <h3>DB/filesystem compensation (C5)</h3>
 *
 * <p>This operation spans two resources — MySQL and the filesystem.
 * A plain {@code @Transactional} cannot roll back a file, so the
 * service registers a transaction synchronization: if the enclosing
 * transaction completes with any status other than COMMITTED (insert
 * failure, outer rollback), the already-stored object is deleted
 * best-effort so no orphan RAW file is left behind. No XA /
 * distributed transaction is introduced.
 *
 * <h3>File type policy (C1/C3)</h3>
 *
 * <p>V1 allowlist by extension (case-insensitive): zip / pdf /
 * jpg / jpeg / png / webp / md / markdown / txt. A declared MIME
 * must belong to the extension's allowed set;
 * {@code application/octet-stream} is accepted as the documented
 * Desktop/OS fallback, and a null declared MIME is treated as that
 * fallback. {@code assetRole} is server-derived: .zip →
 * ORIGINAL_PACKAGE, everything else → ORIGINAL_FILE. Clients can
 * never create PAGE_IMAGE / ATTACHMENT this round. No magic-byte
 * sniffing and no Apache Tika (deep validation belongs to the future
 * ingestion slice).
 *
 * <h3>HTTP mapping</h3>
 *
 * <ul>
 *   <li>{@link #upload} returns {@code null} → 404 (source absent /
 *       not owned); throws {@link ResponseStatusException} for
 *       400 / 413 / 415.</li>
 *   <li>{@link #listMine} returns {@code null} → 404 (source absent /
 *       not owned); empty list when owned but no assets.</li>
 *   <li>{@link #getMine} returns {@code null} → 404 (asset absent /
 *       not owned / wrong source / wrong space).</li>
 * </ul>
 */
@Service
public class SourceAssetService {

    public static final String ROLE_ORIGINAL_PACKAGE = "ORIGINAL_PACKAGE";
    public static final String ROLE_ORIGINAL_FILE = "ORIGINAL_FILE";

    private static final Logger log = LoggerFactory.getLogger(SourceAssetService.class);

    /**
     * V1 extension allowlist → allowed declared MIMEs (R-SOURCE-004).
     * {@code application/octet-stream} is the documented fallback for
     * Desktop/OS uploads that cannot provide a reliable MIME.
     */
    private static final Map<String, Set<String>> ALLOWED_MIME_BY_EXTENSION = Map.of(
            "zip", Set.of("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            "pdf", Set.of("application/pdf", "application/octet-stream"),
            "jpg", Set.of("image/jpeg", "application/octet-stream"),
            "jpeg", Set.of("image/jpeg", "application/octet-stream"),
            "png", Set.of("image/png", "application/octet-stream"),
            "md", Set.of("text/markdown", "text/plain", "application/octet-stream"),
            "markdown", Set.of("text/markdown", "text/plain", "application/octet-stream"),
            "txt", Set.of("text/plain", "application/octet-stream")
    );

    private static final String FALLBACK_MIME = "application/octet-stream";

    private final SourceAssetMapper sourceAssetMapper;
    private final SourceService sourceService;
    private final StorageService storageService;
    private final long maxUploadBytes;

    public SourceAssetService(SourceAssetMapper sourceAssetMapper,
                              SourceService sourceService,
                              StorageService storageService,
                              UploadProperties uploadProperties) {
        this.sourceAssetMapper = sourceAssetMapper;
        this.sourceService = sourceService;
        this.storageService = storageService;
        this.maxUploadBytes = uploadProperties.getMaxFileSize().toBytes();
    }

    /**
     * Uploads one RAW file as a SourceAsset of the caller's own source.
     *
     * @param ownerSubject authenticated JWT subject
     * @param spaceId      parent space id from the path
     * @param sourceId     parent source id from the path
     * @param file         the multipart file part
     * @return the persisted asset, or {@code null} when the source is
     *         absent / not owned (404)
     */
    @Transactional
    public SourceAsset upload(String ownerSubject,
                              Long spaceId,
                              Long sourceId,
                              MultipartFile file) {
        // 1. Parent source ownership (D1): the same owner-scoped query
        //    the Source slice uses. null → 404, nothing is stored.
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }

        // 2. File validation.
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "file part must not be empty");
        }
        String originalName = sanitizeOriginalName(file.getOriginalFilename());
        if (file.getSize() > maxUploadBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "file exceeds the configured upload limit of " + maxUploadBytes + " bytes");
        }
        String extension = extensionOf(originalName);
        String declaredMime = file.getContentType();
        if (declaredMime != null
                && !ALLOWED_MIME_BY_EXTENSION.get(extension).contains(declaredMime)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "MIME '" + declaredMime + "' is not allowed for ." + extension
                            + " files");
        }
        String mimeType = declaredMime != null ? declaredMime : FALLBACK_MIME;

        // 3. Store RAW bytes (streamed, key server-generated).
        StorageResult stored;
        try (InputStream in = file.getInputStream()) {
            stored = storageService.store(in, new StorageMetadata());
        } catch (IOException e) {
            throw new IllegalStateException("failed to read uploaded file", e);
        }

        // 4. Insert metadata row (timestamps normalized to DATETIME(6)
        //    precision, matching the RUNTIME-FIX-01 convention).
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        SourceAsset asset = new SourceAsset();
        asset.setSpaceId(spaceId);
        asset.setSourceId(sourceId);
        asset.setAssetRole(roleFor(extension));
        asset.setOriginalName(originalName);
        asset.setOriginalRelativePath(null);
        asset.setStorageKey(stored.storageKey());
        asset.setMimeType(mimeType);
        asset.setSizeBytes(stored.sizeBytes());
        asset.setSha256(stored.sha256());
        asset.setCreatedAt(now);

        // 5. Compensation: if this transaction does NOT commit, the
        //    stored object must be removed (filesystem is outside the
        //    DB transaction). Registered BEFORE the insert so an
        //    insert failure is also covered.
        String storageKey = stored.storageKey();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) {
                            try {
                                storageService.delete(storageKey);
                            } catch (RuntimeException e) {
                                log.warn("compensation delete failed for storageKey {}"
                                        + " (orphan RAW file possible)", storageKey, e);
                            }
                        }
                    }
                });

        sourceAssetMapper.insert(asset);
        return asset;
    }

    /**
     * Lists assets of the caller's own source, newest first.
     *
     * @return assets (possibly empty), or {@code null} when the
     *         source is absent / not owned (404)
     */
    public List<SourceAsset> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        return sourceAssetMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

    /**
     * Returns ONE asset of the caller's own source.
     *
     * <p>The mapper query constrains assetId + spaceId + sourceId +
     * owner AND requires {@code source.space_id == asset.space_id} in
     * one JOIN — {@code null} means any of: asset absent, asset under
     * a different source, source in a different space, or space not
     * owned. 404 for all (anti-IDOR, D2).
     */
    public SourceAsset getMine(String ownerSubject,
                               Long spaceId,
                               Long sourceId,
                               Long assetId) {
        return sourceAssetMapper.selectByIdSpaceSourceOwner(assetId, spaceId, sourceId, ownerSubject);
    }

    // ==================== validation helpers ====================

    /**
     * Reduces the client-supplied filename to its basename (display /
     * audit only — NEVER a physical path). Handles Windows prefixes
     * like {@code C:\fakepath\book.pdf} and POSIX prefixes like
     * {@code folder/book.pdf}. Rejects null / blank / separator-only
     * / over-255 names with 400.
     */
    private String sanitizeOriginalName(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "original filename must not be null");
        }
        String base = raw;
        int lastSlash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            base = base.substring(lastSlash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "");
        if (base.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "original filename must not be blank");
        }
        if (base.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "original filename must not exceed 255 characters");
        }
        return base;
    }

    /**
     * Lower-case extension of {@code originalName} (the basename), or
     * 415 when the file has no extension or the extension is outside
     * the V1 allowlist (R-SOURCE-004).
     */
    private String extensionOf(String originalName) {
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "file must have an allowed extension "
                            + "(zip, pdf, jpg, jpeg, png, md, markdown, txt)");
        }
        String extension = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_MIME_BY_EXTENSION.containsKey(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "unsupported file extension '." + extension + "'");
        }
        return extension;
    }

    /** .zip → ORIGINAL_PACKAGE; everything else in the allowlist → ORIGINAL_FILE. */
    private String roleFor(String extension) {
        return "zip".equals(extension) ? ROLE_ORIGINAL_PACKAGE : ROLE_ORIGINAL_FILE;
    }
}
