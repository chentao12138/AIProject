package com.aistudy.server.ingestion.job.service;

import com.aistudy.server.ingestion.zip.ZipArchiveInspector;
import com.aistudy.server.ingestion.zip.ZipInspectionResult;
import com.aistudy.server.ingestion.zip.ZipSafetyException;
import com.aistudy.server.ingestion.zip.ZipSafetyLimits;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.asset.service.SourceAssetService;
import com.aistudy.server.storage.StorageMetadata;
import com.aistudy.server.storage.StorageResult;
import com.aistudy.server.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * F-12 — idempotent extraction of one ORIGINAL_PACKAGE ZIP asset into
 * per-entry {@code source_asset} rows.
 *
 * <p>Extracted from {@code IngestionJobService.extractZipEntries()} so
 * the retry path can be unit-tested without the full worker graph.
 *
 * <h3>Idempotency rule (space, source, entry path)</h3>
 *
 * <ol>
 *   <li><b>path + sha256 already seen</b> → REUSE the existing row.
 *       The freshly stored duplicate object is deleted immediately
 *       (compensation — see below); no insert happens. A ZIP retry
 *       therefore adds zero rows and keeps the exact storage bytes.</li>
 *   <li><b>path seen, bytes different</b> → explicit NEW VERSION:
 *       a new row with {@code asset_version = max + 1}; the previous
 *       head row is stamped {@code superseded_at} (supersede-and-
 *       retain — nothing is deleted, so pages/blocks/folder entries
 *       bound to the old asset id keep valid provenance).</li>
 *   <li><b>path new</b> → insert at {@code asset_version = 1}.</li>
 * </ol>
 *
 * <p>Why there is no UNIQUE index enforcing (1): V008 deliberately made
 * {@code sha256} non-unique and historical retries have already
 * produced duplicate (path, sha) rows on live data — deleting them to
 * make an index buildable would break FK references. See the V059
 * migration header for the full rationale; the guard is the service
 * probe {@link SourceAssetMapper#selectBySpaceSourcePathAndSha}.
 *
 * <h3>Failure / compensation semantics</h3>
 *
 * <p>The extraction runs OUTSIDE any DB transaction (the worker
 * autocommits each insert), so {@code SourceAssetService}'s
 * transaction synchronization compensation cannot apply. Instead each
 * store/insert pair is wrapped: if the insert (or the supersede probe)
 * fails, the just-stored object is deleted best-effort before the
 * exception propagates, so a failed or stage-resumed run never leaves
 * orphaned RAW bytes without compensation. A failed compensation
 * delete is logged (it is the same orphan-possible warning class the
 * upload path uses).
 *
 * <h3>Residual race</h3>
 *
 * <p>Two DIFFERENT jobs extracting the SAME ZIP into the same source
 * concurrently can still race the probe (job claims serialize one job,
 * not two jobs on one source). The outcome is a benign duplicate row,
 * identical to the pre-fix worst case and strictly rarer; closing it
 * fully requires the deduplicated UNIQUE index noted above.
 */
@Component
public class ZipEntryAssetExtractor {

    private static final Logger log = LoggerFactory.getLogger(ZipEntryAssetExtractor.class);

    private final StorageService storageService;
    private final SourceAssetMapper sourceAssetMapper;

    public ZipEntryAssetExtractor(StorageService storageService,
                                  SourceAssetMapper sourceAssetMapper) {
        this.storageService = storageService;
        this.sourceAssetMapper = sourceAssetMapper;
    }

    /**
     * Extracts every regular, allow-listed entry of one ZIP asset.
     *
     * @param zipAsset the ORIGINAL_PACKAGE asset (storageKey points at
     *                 the RAW ZIP bytes)
     * @return one SourceAsset per entry to hand to the extraction
     *         pipeline — freshly inserted rows AND reused existing rows
     *         (content re-extraction is the pipeline's business, this
     *         class only guarantees zero duplicated ASSET rows/bytes)
     * @throws IOException            on storage / zip IO failure
     * @throws ZipSafetyException     when the archive violates the
     *                                inspection limits (propagated
     *                                unchanged so the job keeps its
     *                                typed ZIP_SAFETY_VIOLATION failure)
     */
    public List<SourceAsset> extractEntries(SourceAsset zipAsset) throws IOException {
        try (InputStream in = storageService.load(zipAsset.getStorageKey())) {
            java.nio.file.Path temp = java.nio.file.Files.createTempFile("aistudy-zip-extract-", ".zip");
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            ZipInspectionResult inspection = ZipArchiveInspector.inspectFile(
                    temp, ZipSafetyLimits.defaults());
            if (!inspection.valid()) {
                java.nio.file.Files.deleteIfExists(temp);
                throw new ZipSafetyException(inspection.violations());
            }

            try (ZipFile zip = new ZipFile(temp.toFile())) {
                Enumeration<? extends ZipEntry> zipEntries = zip.entries();
                List<SourceAsset> entries = new ArrayList<>();
                while (zipEntries.hasMoreElements()) {
                    ZipEntry entry = zipEntries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    if (name.charAt(0) == '/' || name.charAt(0) == '\\'
                            || name.contains("..")) {
                        continue;
                    }

                    String ext = SourceAssetService.extractExtension(name);
                    if (ext == null || !SourceAssetService.isAllowedExtension(ext)) {
                        continue;
                    }

                    try (InputStream entryIn = zip.getInputStream(entry)) {
                        entries.add(extractOneEntry(zipAsset, name, ext, entryIn));
                    }
                }
                return entries;
            } finally {
                java.nio.file.Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * Store → idempotency probe → reuse / new-version / first-insert,
     * with best-effort compensation delete on every failure path.
     * Package-visible for the unit test.
     */
    SourceAsset extractOneEntry(SourceAsset zipAsset, String entryPath,
                                String extension, InputStream entryIn) throws IOException {
        // Bytes must be hashed to be compared, and the storage layer
        // hashes in the same single pass it writes — so the object is
        // stored first and deleted again when the probe shows the
        // (path, sha) tuple already exists. The transient extra object
        // is the compensation window; it never survives this method.
        StorageResult stored = storageService.store(entryIn, new StorageMetadata());
        try {
            SourceAsset existingSameBytes = sourceAssetMapper.selectBySpaceSourcePathAndSha(
                    zipAsset.getSpaceId(), zipAsset.getSourceId(), entryPath, stored.sha256());
            if (existingSameBytes != null) {
                compensate(stored.storageKey());
                return existingSameBytes;
            }

            List<SourceAsset> versions = sourceAssetMapper.selectBySpaceSourceAndPath(
                    zipAsset.getSpaceId(), zipAsset.getSourceId(), entryPath);
            int nextVersion = 1;
            SourceAsset previousHead = null;
            if (versions != null && !versions.isEmpty()) {
                SourceAsset last = versions.get(versions.size() - 1);
                nextVersion = (last.getAssetVersion() == null ? 1 : last.getAssetVersion()) + 1;
                previousHead = versions.stream()
                        .filter(v -> v.getSupersededAt() == null)
                        .reduce((a, b) -> b)
                        .orElse(last);
            }

            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            SourceAsset entryAsset = new SourceAsset();
            entryAsset.setSpaceId(zipAsset.getSpaceId());
            entryAsset.setSourceId(zipAsset.getSourceId());
            entryAsset.setAssetRole(SourceAssetService.roleForExtension(extension));
            entryAsset.setOriginalName(entryPath);
            entryAsset.setOriginalRelativePath(entryPath);
            entryAsset.setStorageKey(stored.storageKey());
            entryAsset.setMimeType(SourceAssetService.mimeForExtension(extension));
            entryAsset.setSizeBytes(stored.sizeBytes());
            entryAsset.setSha256(stored.sha256());
            entryAsset.setAssetVersion(nextVersion);
            entryAsset.setCreatedAt(now);
            sourceAssetMapper.insert(entryAsset);

            if (previousHead != null && previousHead.getId() != null) {
                // Supersede AFTER the new row exists: if this UPDATE
                // fails the new version is still complete and the old
                // head merely stays active — never a lost version.
                sourceAssetMapper.markSuperseded(previousHead.getId(), now);
            }
            return entryAsset;
        } catch (RuntimeException e) {
            compensate(stored.storageKey());
            throw e;
        }
    }

    /**
     * Best-effort removal of bytes whose row was never persisted (or
     * was a detected duplicate). Mirrors the upload path's transaction
     * synchronization compensation, which cannot be used here because
     * the worker is not inside a transaction.
     */
    private void compensate(String storageKey) {
        try {
            storageService.delete(storageKey);
        } catch (RuntimeException e) {
            log.warn("compensation delete failed for storageKey {} (orphan RAW file possible)",
                    storageKey, e);
        }
    }
}
