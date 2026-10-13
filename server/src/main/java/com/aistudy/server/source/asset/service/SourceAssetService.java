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

    /**
     * F-13 — what the client wants to happen when its bytes already
     * exist in the space (matched by SHA-256).
     *
     * <ul>
     *   <li>{@link #SKIP}: do NOT store or insert again — reuse the
     *       existing asset and delete the transient freshly stored copy.
     *       Concurrent SKIP uploads of identical bytes cannot
     *       double-insert: the insert path serializes on
     *       {@code lockSpaceForUpload} (SELECT ... FOR UPDATE on the
     *       space row) followed by the locking probe
     *       {@code selectForUpdateBySpaceAndSha256}, so the loser of
     *       the race blocks until the winner commits and then SEES the
     *       winner's row (locking reads bypass the REPEATABLE READ
     *       snapshot) and reuses it.</li>
     *   <li>{@link #IMPORT_AGAIN}: store and insert a NEW row even
     *       though the hash exists — an explicit, auditable re-import
     *       (the pre-F-13 behaviour, now only via this opt-in).</li>
     * </ul>
     */
    public enum DuplicatePolicy {
        SKIP,
        IMPORT_AGAIN;

        /**
         * Lenient request-shape binding: {@code skip} / {@code importAgain}
         * case-insensitive; {@code null} or blank means the LEGACY
         * single-argument contract (always insert, response just flags
         * the duplicate). Unknown values are rejected with 400 by the
         * controller.
         */
        public static DuplicatePolicy fromRequestParam(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (DuplicatePolicy policy : values()) {
                if (policy.name().equalsIgnoreCase(raw.trim())) {
                    return policy;
                }
            }
            return null;
        }
    }

    /**
     * F-13 — stable typed upload outcome. {@code decision} is one of
     * {@code STORED} (no prior copy of these bytes in the space),
     * {@code REUSED_EXISTING} (SKIP policy hit an existing hash; zero
     * new rows/bytes) or {@code IMPORTED_AGAIN} (explicit re-import of
     * known bytes).
     *
     * @param asset         the asset the client should work with (for
     *                      REUSED_EXISTING this is the pre-EXISTING row,
     *                      possibly under a sibling source of the same
     *                      space — ownership is space-scoped)
     * @param decision      see above
     * @param duplicateOfId pre-existing asset id with the same
     *                      (space, sha256), or {@code null}
     */
    public record UploadOutcome(SourceAsset asset, String decision, Long duplicateOfId) {
        public boolean duplicate() {
            return duplicateOfId != null;
        }
    }

    /**
     * F-13 — dry-run duplicate probe result keyed by (space, sha256)
     * with the display-name cross-check that distinguishes the three
     * pre-upload scenarios.
     *
     * @param scenario          {@code NONE} | {@code SAME_HASH_SAME_NAME}
     *                          | {@code SAME_HASH_DIFFERENT_NAME}
     *                          | {@code SAME_NAME_DIFFERENT_HASH}
     * @param duplicate         true when a (space, sha256) row exists
     * @param matched           the hash-matched asset (null for NONE /
     *                          SAME_NAME_DIFFERENT_HASH)
     * @param nameConflictAsset an own-space asset with the same display
     *                          name but different bytes (null otherwise)
     */
    public record DuplicateProbe(String scenario, boolean duplicate,
                                 SourceAsset matched, SourceAsset nameConflictAsset) {
    }

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
            "webp", Set.of("image/webp", "application/octet-stream"),
            "docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/octet-stream"),
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
     * Owner-scoped RAW binary open for authorized download.
     * Never exposes storageKey; streams from StorageService.
     */
    public record RawAsset(SourceAsset asset, java.io.InputStream stream) {
    }

    public RawAsset openRaw(String ownerSubject, Long spaceId, Long sourceId, Long assetId) {
        SourceAsset asset = getMine(ownerSubject, spaceId, sourceId, assetId);
        if (asset == null) {
            return null;
        }
        try {
            return new RawAsset(asset, storageService.load(asset.getStorageKey()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SourceAsset content not found");
        }
    }

    /**
     * Uploads one RAW file as a SourceAsset of the caller's own source.
     *
     * <p>LEGACY contract (kept byte-for-byte compatible for current
     * clients): no duplicate policy — bytes are always stored and a
     * row always inserted; the outcome merely flags pre-existing
     * copies. Delegates to {@link #uploadWithPolicy} with a
     * {@code null} policy.
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
        UploadOutcome outcome = uploadWithPolicy(ownerSubject, spaceId, sourceId, file, null);
        return outcome == null ? null : outcome.asset();
    }

    /**
     * F-13 — upload with an explicit duplicate policy.
     *
     * <h4>Deterministic outcomes</h4>
     * <ol>
     *   <li>policy {@code null} (legacy) → always insert; decision
     *       {@code STORED}, or {@code IMPORTED_AGAIN} + duplicateOfId
     *       when the same (space, sha256) already existed. No space
     *       lock is taken on this path — its behaviour must not change.</li>
     *   <li>SKIP + hash exists → transient stored copy is deleted, the
     *       existing row is returned with {@code REUSED_EXISTING};
     *       zero new rows, zero new surviving bytes.</li>
     *   <li>SKIP/IMPORT_AGAIN + hash new → {@code STORED}.</li>
     *   <li>IMPORT_AGAIN + hash exists → new row {@code IMPORTED_AGAIN}
     *       (same name or different name — both are a deliberate
     *       re-import; the name is display metadata, per-row unique
     *       names were never a constraint).</li>
     *   <li>Same NAME + different hash → never a hash duplicate:
     *       stored as a fresh asset ({@code STORED}); the precheck
     *       endpoint surfaces it as SAME_NAME_DIFFERENT_HASH so the
     *       client can warn the user BEFORE uploading.</li>
     * </ol>
     *
     * <p>Race handling (case 2 with two concurrent SKIP uploads): see
     * {@link DuplicatePolicy} — FOR-UPDATE serialization, no
     * double-insert. Compensation: the pre-insert synchronization (C5)
     * deletes the stored object when the transaction aborts; the SKIP
     * reuse path deletes it inline.
     *
     * @return outcome, or {@code null} when the source is absent / not
     *         owned (404 → caller maps)
     */
    @Transactional
    public UploadOutcome uploadWithPolicy(String ownerSubject,
                                          Long spaceId,
                                          Long sourceId,
                                          MultipartFile file,
                                          DuplicatePolicy policy) {
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

        // 4. F-13 policy branch. The locking probe (and with it the
        //    no-double-insert guarantee) applies ONLY when a policy is
        //    supplied — the legacy null-policy path keeps its exact
        //    old statement sequence.
        if (policy != null) {
            sourceAssetMapper.lockSpaceForUpload(spaceId);
            SourceAsset hashMatch =
                    sourceAssetMapper.selectForUpdateBySpaceAndSha256(spaceId, stored.sha256());
            if (hashMatch != null && policy == DuplicatePolicy.SKIP) {
                // Reuse: the transient duplicate object must not survive.
                deleteTransient(stored.storageKey());
                return new UploadOutcome(hashMatch, "REUSED_EXISTING", hashMatch.getId());
            }
            SourceAsset insertProbe = hashMatch;
            SourceAsset created = insertAndCompensate(ownerSubject, spaceId, sourceId, originalName,
                    extension, mimeType, stored);
            return new UploadOutcome(created, insertProbe == null ? "STORED" : "IMPORTED_AGAIN",
                    insertProbe == null ? null : insertProbe.getId());
        }

        // 5. Legacy path: insert unconditionally, then flag duplicates.
        SourceAsset created = insertAndCompensate(ownerSubject, spaceId, sourceId, originalName,
                extension, mimeType, stored);
        SourceAsset prior = sourceAssetMapper.selectBySpaceAndSha256(spaceId, created.getSha256(), ownerSubject);
        Long duplicateOfId = prior != null && !prior.getId().equals(created.getId())
                ? prior.getId() : null;
        return new UploadOutcome(created, duplicateOfId == null ? "STORED" : "IMPORTED_AGAIN",
                duplicateOfId);
    }

    /** Best-effort inline delete of a stored object that will never own a row. */
    private void deleteTransient(String storageKey) {
        try {
            storageService.delete(storageKey);
        } catch (RuntimeException e) {
            log.warn("transient-object delete failed for storageKey {} (orphan RAW file possible)",
                    storageKey, e);
        }
    }

    /**
     * Shared insert + C5 compensation: builds the metadata row
     * (timestamps DATETIME(6)-normalized) and registers the
     * rollback delete BEFORE the insert, so an insert failure is
     * covered exactly like before F-13.
     */
    private SourceAsset insertAndCompensate(String ownerSubject,
                                            Long spaceId,
                                            Long sourceId,
                                            String originalName,
                                            String extension,
                                            String mimeType,
                                            StorageResult stored) {
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
        asset.setAssetVersion(1);
        asset.setCreatedAt(now);

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

    public SourceAsset checkDuplicate(String ownerSubject, Long spaceId, Long sourceId, String sha256) {
        return sourceAssetMapper.selectBySpaceAndSha256(spaceId, sha256, ownerSubject);
    }

    /**
     * F-13 — dry-run duplicate probe keyed by (space, sha256), plus the
     * optional display-name cross-check that separates the three
     * pre-upload scenarios. Call this BEFORE uploading to decide the
     * {@link DuplicatePolicy}; the answer is stable for each case:
     *
     * <ul>
     *   <li>SAME_HASH_SAME_NAME → exact re-upload; SKIP saves bytes.</li>
     *   <li>SAME_HASH_DIFFERENT_NAME → renamed copy; SKIP reuses the
     *       existing row (which keeps the OLD name — choose
     *       IMPORT_AGAIN when the new display name must own a row).</li>
     *   <li>SAME_NAME_DIFFERENT_HASH → content change, NOT a duplicate;
     *       upload normally, expect STORED.</li>
     *   <li>NONE → fresh bytes; upload normally, expect STORED.</li>
     * </ul>
     *
     * <p>The fourth real case — concurrent upload of identical bytes —
     * cannot be pre-checked (it is a race, not a state); it is handled
     * at upload time by the FOR-UPDATE serialization documented on
     * {@link DuplicatePolicy#SKIP}.
     *
     * @param ownerSubject authenticated JWT subject
     * @param spaceId     space the upload would target (ownership
     *                    enforced through the owner-scoped JOIN)
     * @param sha256      lowercase hex SHA-256 computed by the client
     * @param name        intended display name, may be {@code null}
     * @return probe (never null); 400 when sha256 is malformed
     */
    public DuplicateProbe precheckDuplicate(String ownerSubject,
                                            Long spaceId,
                                            String sha256,
                                            String name) {
        if (sha256 == null || sha256.length() != 64
                || !sha256.chars().allMatch(c -> Character.isDigit(c)
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sha256 must be 64 lowercase hex characters");
        }
        SourceAsset hashMatch = sourceAssetMapper.selectBySpaceAndSha256(
                spaceId, sha256.toLowerCase(Locale.ROOT), ownerSubject);
        if (hashMatch != null) {
            String scenario = name != null && !name.isBlank()
                    && name.equals(hashMatch.getOriginalName())
                    ? "SAME_HASH_SAME_NAME" : "SAME_HASH_DIFFERENT_NAME";
            return new DuplicateProbe(scenario, true, hashMatch, null);
        }
        if (name != null && !name.isBlank()) {
            SourceAsset nameMatch = sourceAssetMapper.selectFirstBySpaceAndName(
                    spaceId, name, ownerSubject);
            if (nameMatch != null) {
                return new DuplicateProbe("SAME_NAME_DIFFERENT_HASH", false, null, nameMatch);
            }
        }
        return new DuplicateProbe("NONE", false, null, null);
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

    public static String extractExtension(String originalName) {
        if (originalName == null) return null;
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) return null;
        return originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String mimeForExtension(String extension) {
        if (extension == null) return FALLBACK_MIME;
        Set<String> mimes = ALLOWED_MIME_BY_EXTENSION.getOrDefault(extension.toLowerCase(Locale.ROOT), Set.of(FALLBACK_MIME));
        return mimes.iterator().next();
    }

    public static String roleForExtension(String extension) {
        if (extension == null) return ROLE_ORIGINAL_FILE;
        return "zip".equals(extension.toLowerCase(Locale.ROOT)) ? ROLE_ORIGINAL_PACKAGE : ROLE_ORIGINAL_FILE;
    }

    public static boolean isAllowedExtension(String ext) {
        return ALLOWED_MIME_BY_EXTENSION.containsKey(ext.toLowerCase(Locale.ROOT));
    }
}
