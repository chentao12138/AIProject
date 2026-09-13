package com.aistudy.server.ingestion.job.service;

import com.aistudy.server.ingestion.extract.ContentExtractionService;
import com.aistudy.server.ingestion.extract.IngestionParseException;
import com.aistudy.server.ingestion.extract.TxtMarkdownContentParser.ParsedDocument;
import com.aistudy.server.ingestion.image.ImageContentExtractionService;
import com.aistudy.server.ingestion.image.ImageExtractionException;
import com.aistudy.server.ingestion.job.entity.IngestionJob;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.ingestion.pdf.PdfContentExtractionService;
import com.aistudy.server.ingestion.pdf.PdfExtractionException;
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
 * for one ingestion attempt) plus the synchronous ZIP safety
 * validation of the V1 pipeline.
 *
 * <p>V1 dispatches to TXT/Markdown, PDF, or image ingestion. Each
 * path validates the asset, extracts deterministic metadata/text,
 * and persists one SourcePage plus optional ContentBlocks inside
 * the surrounding job transaction.
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
    private final PdfContentExtractionService pdfContentExtractionService;
    private final ImageContentExtractionService imageContentExtractionService;
    private final ZipSafetyLimits zipLimits;

    public IngestionJobService(IngestionJobMapper ingestionJobMapper,
                               SourceService sourceService,
                               SourceAssetService sourceAssetService,
                               StorageService storageService,
                               ContentExtractionService contentExtractionService,
                               PdfContentExtractionService pdfContentExtractionService,
                               ImageContentExtractionService imageContentExtractionService,
                               @Value("${aistudy.ingestion.zip.max-entries:10000}") int maxEntries,
                               @Value("${aistudy.ingestion.zip.max-entry-uncompressed-bytes:4GB}") DataSize maxEntryBytes,
                               @Value("${aistudy.ingestion.zip.max-total-uncompressed-bytes:16GB}") DataSize maxTotalBytes,
                               @Value("${aistudy.ingestion.zip.max-compression-ratio:200}") long maxCompressionRatio) {
        this.ingestionJobMapper = ingestionJobMapper;
        this.sourceService = sourceService;
        this.sourceAssetService = sourceAssetService;
        this.storageService = storageService;
        this.contentExtractionService = contentExtractionService;
        this.pdfContentExtractionService = pdfContentExtractionService;
        this.imageContentExtractionService = imageContentExtractionService;
        this.zipLimits = new ZipSafetyLimits(maxEntries, maxEntryBytes.toBytes(),
                maxTotalBytes.toBytes(), maxCompressionRatio);
    }

    /**
     * Creates one ingestion job for the caller's own source + asset
     * and runs the V1 pipeline steps that exist (format dispatch).
     *
     * @return the persisted job, or {@code null} when source/asset is
     *         absent or not owned (404)
     * @throws ResponseStatusException 409 when the asset already has a
     *         PENDING/RUNNING/SUCCEEDED job; 422 INGESTION_NOT_READY
     *         when the asset format has no wired pipeline
     */
    @Transactional
    public IngestionJob create(String ownerSubject,
                               Long spaceId,
                               Long sourceId,
                               Long assetId) {
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        SourceAsset asset = sourceAssetService.getMine(ownerSubject, spaceId, sourceId, assetId);
        if (asset == null) {
            return null;
        }

        if (ingestionJobMapper.countActiveOrSucceeded(spaceId, sourceId, assetId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an ingestion job for this asset already exists "
                            + "(pending, running or succeeded)");
        }

        if (!isSupportedAsset(asset)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INGESTION_NOT_READY: no ingestion pipeline for this asset format yet");
        }

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

        if (isZipAsset(asset)) {
            runZipSafetyGate(job, asset);
        } else if (isTextAsset(asset)) {
            runTextPipeline(job, asset);
        } else if (isPdfAsset(asset)) {
            runPdfPipeline(job, asset);
        } else if (isImageAsset(asset)) {
            runImagePipeline(job, asset);
        } else {
            markFailed(job, IngestionErrorCode.UNSUPPORTED_FORMAT,
                    "asset format has no ingestion pipeline");
        }
        return job;
    }

    public IngestionJob getMine(String ownerSubject, Long spaceId, Long jobId) {
        return ingestionJobMapper.selectByIdSpaceOwner(jobId, spaceId, ownerSubject);
    }

    public List<IngestionJob> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        return ingestionJobMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

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
            } else if (isPdfAsset(asset)) {
                runPdfPipeline(job, asset);
            } else if (isImageAsset(asset)) {
                runImagePipeline(job, asset);
            } else {
                markFailed(job, IngestionErrorCode.UNSUPPORTED_FORMAT,
                        "asset format has no ingestion pipeline");
            }
        }
        return job;
    }

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

    // ==================== pipeline steps ====================

    private void runZipSafetyGate(IngestionJob job, SourceAsset asset) {
        ZipInspectionResult result = inspectZipAsset(asset);
        if (!result.valid()) {
            markFailed(job, IngestionErrorCode.ZIP_SAFETY_VIOLATION,
                    zipViolationSummary(result.violations()));
        }
    }

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

    private void runPdfPipeline(IngestionJob job, SourceAsset asset) {
        markRunning(job);
        try {
            pdfContentExtractionService.extractAndPersist(asset);
            markSucceeded(job);
        } catch (PdfExtractionException e) {
            markFailed(job, e.errorCode(), e.safeMessage());
        }
    }

    private void runImagePipeline(IngestionJob job, SourceAsset asset) {
        markRunning(job);
        try {
            imageContentExtractionService.extractAndPersist(asset);
            markSucceeded(job);
        } catch (ImageExtractionException e) {
            markFailed(job, e.errorCode(), e.safeMessage());
        }
    }

    // ==================== helpers ====================

    private static void requireStatus(IngestionJob job, String expected, String action) {
        if (!expected.equals(job.getStatus())) {
            throw new IllegalStateException("cannot " + action + " job " + job.getId()
                    + " in status " + job.getStatus() + " (expected " + expected + ")");
        }
    }

    private ZipInspectionResult inspectZipAsset(SourceAsset asset) {
        try (InputStream in = storageService.load(asset.getStorageKey())) {
            return ZipArchiveInspector.inspect(in, zipLimits);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load asset for ZIP inspection", e);
        }
    }

    private static String zipViolationSummary(List<ZipViolation> violations) {
        return boundMessage(violations.stream()
                .limit(5)
                .map(ZipViolation::message)
                .collect(Collectors.joining("; ")));
    }

    private static String boundMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private static boolean isSupportedAsset(SourceAsset asset) {
        return isZipAsset(asset) || isTextAsset(asset) || isPdfAsset(asset) || isImageAsset(asset);
    }

    private static boolean isZipAsset(SourceAsset asset) {
        return SourceAssetService.ROLE_ORIGINAL_PACKAGE.equals(asset.getAssetRole());
    }

    private static boolean isTextAsset(SourceAsset asset) {
        String name = asset.getOriginalName() == null ? "" : asset.getOriginalName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isPdfAsset(SourceAsset asset) {
        String name = asset.getOriginalName() == null ? "" : asset.getOriginalName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return "pdf".equals(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isImageAsset(SourceAsset asset) {
        if (asset.getMimeType() == null) {
            return false;
        }
        return asset.getMimeType().equals("image/png")
                || asset.getMimeType().equals("image/jpeg");
    }
}
