package com.aistudy.server.ingestion.job.service;

import com.aistudy.server.ingestion.extract.ContentExtractionService;
import com.aistudy.server.ingestion.extract.IngestionParseException;
import com.aistudy.server.ingestion.extract.TxtMarkdownContentParser.ParsedDocument;
import com.aistudy.server.ingestion.job.entity.IngestionJob;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.ingestion.zip.ZipArchiveInspector;
import com.aistudy.server.ingestion.zip.ZipInspectionResult;
import com.aistudy.server.ingestion.zip.ZipSafetyLimits;
import com.aistudy.server.ingestion.zip.ZipViolation;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.service.SourceAssetService;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BUSINESS-005 — application service for IngestionJob (process state
 * for one ingestion attempt, data-model.md §7.1) plus the synchronous
 * ZIP safety validation of the V1 pipeline.
 *
 * <h3>V1 lifecycle (BACKEND_AUTORUN_4H.md §5.3)</h3>
 *
 * <pre>
 * PENDING --start--> RUNNING --finish--> SUCCEEDED
 *    |                   |
 *    +--fail--> FAILED --+--fail--> FAILED
 *                  |
 *                  +--retry--> PENDING (retryCount++)
 * </pre>
 *
 * <p>{@code status} is the coarse lifecycle state; {@code stage} is
 * the pipeline position (content-ingestion.md §6). PARTIAL_FAILED is
 * deferred — V1 ingestion is all-or-nothing per asset.
 *
 * <h3>Create-time pipeline dispatch (BUSINESS-005 + 006)</h3>
 *
 * <p>Creating a job dispatches on the asset format and runs the
 * pipeline steps that exist, synchronously (fast deterministic
 * steps; async infra is deferred until long-running extraction
 * exists — architecture.md §6.2):
 *
 * <ul>
 *   <li><strong>ZIP</strong> (assetRole ORIGINAL_PACKAGE):
 *       {@link ZipArchiveInspector} on the RAW bytes
 *       (content-ingestion.md §7 step 2). Valid → job stays PENDING
 *       (extraction is NOT part of this window — BACKEND_AUTORUN_4H.md
 *       §5.4); unsafe/unreadable → job FAILED with
 *       {@link IngestionErrorCode#ZIP_SAFETY_VIOLATION}.</li>
 *   <li><strong>TXT / Markdown</strong>: the deterministic parser
 *       runs (markRunning → parse → persist page+blocks → markSucceeded);
 *       content problems → FAILED with a stable code + SAFE message
 *       (ENCODING_ERROR / DOCUMENT_TOO_LARGE); nothing is persisted
 *       on failure.</li>
 *   <li><strong>anything else</strong> (PDF/image/…): no pipeline
 *       exists yet → 422 {@code INGESTION_NOT_READY}, no job row.</li>
 * </ul>
 *
 * <p>Duplicate guard: an asset that already has a PENDING / RUNNING /
 * SUCCEEDED job cannot be re-ingested (409) — silent duplicate
 * pages/blocks are impossible. A FAILED job does NOT block a new
 * attempt (retry or fresh create).</p>
 *
 * <h3>Ownership (D1/D2)</h3>
 *
 * <p>Create validates parent source via {@link SourceService#getMine}
 * and the asset via {@link SourceAssetService#getMine} (assetId +
 * spaceId + sourceId + owner in one JOIN) FIRST — any mismatch is a
 * 404 before any row is written. Reads are owner-scoped SQL JOINs
 * (id + spaceId + owner, plus source↔space consistency).
 */
@Service
public class IngestionJobService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String STAGE_QUEUED = "QUEUED";
    public static final String STAGE_IMPORTING = "IMPORTING";
    public static final String STAGE_PUBLISHED = "PUBLISHED";

    /** error_message column is VARCHAR(1000) — bound before persist. */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private static final Logger log = LoggerFactory.getLogger(IngestionJobService.class);

    /** V1 text formats whose pipeline is wired (mirrors ContentExtractionService). */
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown");

    private final IngestionJobMapper ingestionJobMapper;
    private final SourceService sourceService;
    private final SourceAssetService sourceAssetService;
    private final StorageService storageService;
    private final ContentExtractionService contentExtractionService;
    private final ZipSafetyLimits zipLimits;

    public IngestionJobService(IngestionJobMapper ingestionJobMapper,
                               SourceService sourceService,
                               SourceAssetService sourceAssetService,
                               StorageService storageService,
                               ContentExtractionService contentExtractionService,
                               @Value("${aistudy.ingestion.zip.max-entries:10000}") int maxEntries,
                               @Value("${aistudy.ingestion.zip.max-entry-uncompressed-bytes:4GB}") DataSize maxEntryBytes,
                               @Value("${aistudy.ingestion.zip.max-total-uncompressed-bytes:16GB}") DataSize maxTotalBytes,
                               @Value("${aistudy.ingestion.zip.max-compression-ratio:200}") long maxCompressionRatio) {
        this.ingestionJobMapper = ingestionJobMapper;
        this.sourceService = sourceService;
        this.sourceAssetService = sourceAssetService;
        this.storageService = storageService;
        this.contentExtractionService = contentExtractionService;
        this.zipLimits = new ZipSafetyLimits(maxEntries, maxEntryBytes.toBytes(),
                maxTotalBytes.toBytes(), maxCompressionRatio);
    }

    /**
     * Creates one ingestion job for the caller's own source + asset
     * and runs the V1 pipeline steps that exist (format dispatch):
     * ZIP safety inspection for ORIGINAL_PACKAGE assets, TXT/MD
     * deterministic extraction for text assets.
     *
     * @return the persisted job (PENDING for validated ZIPs; RUNNING
     *         → SUCCEEDED for ingested text; FAILED when a pipeline
     *         step rejects the content), or {@code null} when
     *         source/asset is absent or not owned (404)
     * @throws ResponseStatusException 409 when the asset already has a
     *         PENDING/RUNNING/SUCCEEDED job; 422 INGESTION_NOT_READY
     *         when the asset format has no pipeline (PDF/image/…)
     */
    @Transactional
    public IngestionJob create(String ownerSubject,
                               Long spaceId,
                               Long sourceId,
                               Long assetId) {
        // 1. Parent source + asset ownership (D1): null → 404 before
        //    any row is written.
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        SourceAsset asset = sourceAssetService.getMine(ownerSubject, spaceId, sourceId, assetId);
        if (asset == null) {
            return null;
        }

        // 2. Duplicate guard: an asset with a PENDING/RUNNING/SUCCEEDED
        //    job must not be re-ingested (silent duplicate content).
        if (ingestionJobMapper.countActiveOrSucceeded(spaceId, sourceId, assetId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an ingestion job for this asset already exists "
                            + "(pending, running or succeeded)");
        }

        // 3. Format gate: only formats with a wired pipeline are
        //    accepted — no zombie PENDING jobs for PDF/images.
        if (!isZipAsset(asset) && !isTextAsset(asset)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INGESTION_NOT_READY: no ingestion pipeline for this asset format yet "
                            + "(pdf/image ingestion is not implemented)");
        }

        // 4. Insert the job in PENDING/QUEUED.
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        IngestionJob job = new IngestionJob();
        job.setSpaceId(spaceId);
        job.setSourceId(sourceId);
        job.setAssetId(assetId);
        job.setStatus(STATUS_PENDING);
        job.setStage(STAGE_QUEUED);
        job.setProgressPercent(0);
        job.setRetryCount(0);
        job.setCreatedByUserId(ownerSubject);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        ingestionJobMapper.insert(job);

        // 5. V1 pipeline dispatch.
        if (isZipAsset(asset)) {
            runZipSafetyGate(job, asset);
        } else {
            runTextPipeline(job, asset);
        }
        return job;
    }

    /**
     * Returns ONE job of the caller's own space (space-scoped
     * endpoint, api-guidelines.md §6).
     *
     * @return the job, or {@code null} when absent / not owned /
     *         cross-space (404)
     */
    public IngestionJob getMine(String ownerSubject, Long spaceId, Long jobId) {
        return ingestionJobMapper.selectByIdSpaceOwner(jobId, spaceId, ownerSubject);
    }

    /**
     * Lists the job history of the caller's own source, newest first.
     *
     * @return jobs (possibly empty), or {@code null} when the source
     *         is absent / not owned (404)
     */
    public List<IngestionJob> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        return ingestionJobMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

    /**
     * Retries a FAILED job (R-INGEST-010): FAILED → PENDING,
     * {@code retryCount++}, error fields cleared, then the V1
     * pipeline steps run again (ZIP safety gate re-inspects).
     *
     * @return the re-queued job (possibly FAILED again by the safety
     *         gate), or {@code null} when absent / not owned (404);
     *         409 when the job is not in FAILED state
     */
    @Transactional
    public IngestionJob retry(String ownerSubject, Long spaceId, Long jobId) {
        IngestionJob job = getMine(ownerSubject, spaceId, jobId);
        if (job == null) {
            return null;
        }
        if (!STATUS_FAILED.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only FAILED jobs can be retried (current status: " + job.getStatus() + ")");
        }
        int retryCount = job.getRetryCount() + 1;
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ingestionJobMapper.resetForRetry(job.getId(), retryCount, now);

        job.setStatus(STATUS_PENDING);
        job.setStage(STAGE_QUEUED);
        job.setProgressPercent(0);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job.setRetryCount(retryCount);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setUpdatedAt(now);

        SourceAsset asset = sourceAssetService.getMine(
                ownerSubject, spaceId, job.getSourceId(), job.getAssetId());
        if (asset != null) {
            if (isZipAsset(asset)) {
                runZipSafetyGate(job, asset);
            } else if (isTextAsset(asset)) {
                runTextPipeline(job, asset);
            } else {
                markFailed(job, IngestionErrorCode.UNSUPPORTED_FORMAT,
                        "asset format has no ingestion pipeline");
            }
        }
        return job;
    }

    // ==================== pipeline transitions (V1) ====================

    /**
     * PENDING → RUNNING (startedAt set, stage → IMPORTING). Called by
     * the ingestion executor (BUSINESS-006 TXT/Markdown parser).
     *
     * @throws IllegalStateException when the transition is invalid
     */
    @Transactional
    public IngestionJob markRunning(IngestionJob job) {
        requireStatus(job, STATUS_PENDING, "start");
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        job.setStatus(STATUS_RUNNING);
        job.setStage(STAGE_IMPORTING);
        job.setStartedAt(now);
        job.setUpdatedAt(now);
        ingestionJobMapper.updateById(job);
        return job;
    }

    /**
     * RUNNING → SUCCEEDED (progress 100, stage → PUBLISHED,
     * finishedAt set).
     *
     * @throws IllegalStateException when the transition is invalid
     */
    @Transactional
    public IngestionJob markSucceeded(IngestionJob job) {
        requireStatus(job, STATUS_RUNNING, "finish");
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        job.setStatus(STATUS_SUCCEEDED);
        job.setStage(STAGE_PUBLISHED);
        job.setProgressPercent(100);
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        ingestionJobMapper.updateById(job);
        return job;
    }

    /**
     * PENDING|RUNNING → FAILED with a stable error code + SAFE
     * message (no stack trace, no absolute paths; bounded to the
     * column size). finishedAt is set.
     *
     * @throws IllegalStateException when the transition is invalid
     */
    @Transactional
    public IngestionJob markFailed(IngestionJob job,
                                   IngestionErrorCode errorCode,
                                   String safeMessage) {
        if (!STATUS_PENDING.equals(job.getStatus()) && !STATUS_RUNNING.equals(job.getStatus())) {
            throw new IllegalStateException("cannot fail job " + job.getId()
                    + " in status " + job.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        job.setStatus(STATUS_FAILED);
        job.setFinishedAt(now);
        job.setErrorCode(errorCode.code());
        job.setErrorMessage(boundMessage(safeMessage));
        job.setUpdatedAt(now);
        ingestionJobMapper.updateById(job);
        return job;
    }

    // ==================== helpers ====================

    private static void requireStatus(IngestionJob job, String expected, String action) {
        if (!expected.equals(job.getStatus())) {
            throw new IllegalStateException("cannot " + action + " job " + job.getId()
                    + " in status " + job.getStatus() + " (expected " + expected + ")");
        }
    }

    /** ORIGINAL_PACKAGE (server-derived for .zip, BUSINESS-004) ⇒ ZIP asset. */
    private static boolean isZipAsset(SourceAsset asset) {
        return SourceAssetService.ROLE_ORIGINAL_PACKAGE.equals(asset.getAssetRole());
    }

    /** V1 text formats (extension of the display basename, lowercase). */
    private static boolean isTextAsset(SourceAsset asset) {
        String name = asset.getOriginalName() == null ? "" : asset.getOriginalName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** ZIP pipeline step: safety inspection; invalid → FAILED job. */
    private void runZipSafetyGate(IngestionJob job, SourceAsset asset) {
        ZipInspectionResult result = inspectZipAsset(asset);
        if (!result.valid()) {
            markFailed(job, IngestionErrorCode.ZIP_SAFETY_VIOLATION,
                    zipViolationSummary(result.violations()));
        }
    }

    /**
     * TXT/MD pipeline step (BUSINESS-006): RUNNING → parse →
     * persist page+blocks → SUCCEEDED; content problems → FAILED
     * with a stable code + SAFE message. Persistence happens only
     * after a complete parse, so a FAILED job never leaves partial
     * content behind.
     */
    private void runTextPipeline(IngestionJob job, SourceAsset asset) {
        markRunning(job);
        try {
            ParsedDocument doc = contentExtractionService.extractText(asset);
            contentExtractionService.persistPageAndBlocks(asset, doc);
            markSucceeded(job);
        } catch (IngestionParseException e) {
            markFailed(job, e.errorCode(), e.safeMessage());
        }
    }

    private ZipInspectionResult inspectZipAsset(SourceAsset asset) {
        try (InputStream in = storageService.load(asset.getStorageKey())) {
            return ZipArchiveInspector.inspect(in, zipLimits);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to load asset for ZIP inspection", e);
        }
    }

    /** Safe, bounded summary of violations for the job's error_message. */
    private static String zipViolationSummary(List<ZipViolation> violations) {
        String summary = violations.stream()
                .limit(5)
                .map(ZipViolation::message)
                .collect(Collectors.joining("; "));
        return boundMessage(summary);
    }

    private static String boundMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
