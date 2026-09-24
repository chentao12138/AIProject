package com.aistudy.server.ingestion.job.service;

import com.aistudy.server.config.properties.IngestionWorkerProperties;
import com.aistudy.server.ingestion.extract.DocxContentParser;
import com.aistudy.server.ingestion.extract.TextMarkdownContentParser;
import com.aistudy.server.ingestion.image.ImageContentExtractionService;
import com.aistudy.server.ingestion.job.entity.IngestionJob;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.ingestion.pdf.PdfContentExtractionService;
import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.aistudy.server.ingestion.revision.service.ExtractionRevisionService;
import com.aistudy.server.ingestion.zip.ZipArchiveInspector;
import com.aistudy.server.ingestion.zip.ZipInspectionResult;
import com.aistudy.server.ingestion.zip.ZipSafetyException;
import com.aistudy.server.ingestion.zip.ZipSafetyLimits;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.asset.service.SourceAssetService;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.storage.StorageMetadata;
import com.aistudy.server.storage.StorageResult;
import com.aistudy.server.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class IngestionJobService {

    public enum IngestionStatus {
        QUEUED, IMPORTING, EXTRACTING, STRUCTURING, AI_PROCESSING, NEEDS_REVIEW, PUBLISHED, PARTIAL_FAILED, FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(IngestionJobService.class);

    private final IngestionJobMapper ingestionJobMapper;
    private final SourceAssetMapper sourceAssetMapper;
    private final SourceMapper sourceMapper;
    private final LearningSpaceService learningSpaceService;
    private final SourceService sourceService;
    private final SourceAssetService sourceAssetService;
    private final ThreadPoolTaskExecutor ingestionWorkerExecutor;
    private final IngestionWorkerProperties workerProperties;
    private final IngestionRevisionPublisher revisionPublisher;
    private final PdfContentExtractionService pdfContentExtractionService;
    private final ImageContentExtractionService imageContentExtractionService;
    private final TextMarkdownContentParser textMarkdownContentParser;
    private final DocxContentParser docxContentParser;
    private final ExtractionRevisionService extractionRevisionService;
    private final StorageService storageService;
    private final com.aistudy.server.ingestion.issue.service.IngestionIssueService ingestionIssueService;

    public IngestionJobService(IngestionJobMapper ingestionJobMapper,
                               SourceAssetMapper sourceAssetMapper,
                               SourceMapper sourceMapper,
                               LearningSpaceService learningSpaceService,
                               SourceService sourceService,
                               SourceAssetService sourceAssetService,
                               @org.springframework.beans.factory.annotation.Qualifier("ingestionWorkerExecutor")
                               ThreadPoolTaskExecutor ingestionWorkerExecutor,
                               IngestionWorkerProperties workerProperties,
                               IngestionRevisionPublisher revisionPublisher,
                               PdfContentExtractionService pdfContentExtractionService,
                               ImageContentExtractionService imageContentExtractionService,
                               TextMarkdownContentParser textMarkdownContentParser,
                               DocxContentParser docxContentParser,
                               ExtractionRevisionService extractionRevisionService,
                               StorageService storageService,
                               com.aistudy.server.ingestion.issue.service.IngestionIssueService ingestionIssueService) {
        this.ingestionJobMapper = ingestionJobMapper;
        this.sourceAssetMapper = sourceAssetMapper;
        this.sourceMapper = sourceMapper;
        this.learningSpaceService = learningSpaceService;
        this.sourceService = sourceService;
        this.sourceAssetService = sourceAssetService;
        this.ingestionWorkerExecutor = ingestionWorkerExecutor;
        this.workerProperties = workerProperties;
        this.revisionPublisher = revisionPublisher;
        this.pdfContentExtractionService = pdfContentExtractionService;
        this.imageContentExtractionService = imageContentExtractionService;
        this.textMarkdownContentParser = textMarkdownContentParser;
        this.docxContentParser = docxContentParser;
        this.extractionRevisionService = extractionRevisionService;
        this.storageService = storageService;
        this.ingestionIssueService = ingestionIssueService;
    }

    @Transactional
    public IngestionJob create(String ownerSubject, Long spaceId, Long sourceId, Long assetId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        Source source = sourceMapper.selectByIdAndSpaceAndOwner(sourceId, spaceId, ownerSubject);
        if (source == null) {
            return null;
        }
        List<SourceAsset> assets = sourceAssetMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
        if (assets == null || assets.isEmpty()) {
            return null;
        }
        SourceAsset asset = assets.stream()
                .filter(candidate -> candidate.getId() != null && candidate.getId().equals(assetId))
                .findFirst()
                .orElse(null);
        if (asset == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        IngestionJob job = new IngestionJob();
        job.setSpaceId(spaceId);
        job.setSourceId(sourceId);
        job.setAssetId(asset.getId());
        job.setCreatedByUserId(ownerSubject);
        job.setStatus(IngestionStatus.QUEUED.name());
        job.setStage("IMPORT");
        job.setProgressPercent(0);
        job.setRetryCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        ingestionJobMapper.insert(job);
        submit(job.getId());
        return job;
    }

    /**
     * Hands the job to the worker pool AFTER the caller's transaction commits.
     *
     * <p>Submitting inside {@code @Transactional} raced the insert: the worker
     * could not yet see the row, {@code claimQueued} matched nothing, and the
     * job stayed QUEUED with nobody scheduled to pick it up.
     *
     * <p>A rejected submission is not an error the caller sees: the row remains
     * QUEUED and the periodic recovery drain dispatches it once the pool frees.
     */
    private void submit(Long jobId) {
        Runnable dispatch = () -> {
            try {
                ingestionWorkerExecutor.execute(() -> processJob(jobId));
            } catch (RuntimeException ex) {
                log.warn("Ingestion job {} queued for later dispatch: {}", jobId, ex.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    public List<IngestionJob> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return ingestionJobMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

    public IngestionJob getMine(String ownerSubject, Long spaceId, Long jobId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return ingestionJobMapper.selectByIdSpaceOwner(jobId, spaceId, ownerSubject);
    }

    private static final String WORKER_ID =
            "worker-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    private void processJob(Long jobId) {
        IngestionJob job = ingestionJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int claimed = ingestionJobMapper.claimQueued(
                jobId, WORKER_ID, now, now.minus(workerProperties.getStaleLease()));
        if (claimed == 0) {
            // Another worker owns this job, or it is not QUEUED.
            return;
        }
        job = ingestionJobMapper.selectById(jobId);
        try {
            // Stage-aware resume: skip IMPORT artifacts already marked complete.
            boolean importDone = "IMPORT".equalsIgnoreCase(job.getLastStageStatus());
            if (!importDone) {
                updateStatus(job, IngestionStatus.IMPORTING.name(), "IMPORT", 10);
            }

            List<SourceAsset> assets = discoverAssets(job);
            if (assets.isEmpty()) {
                ingestionJobMapper.markTerminalFailure(job.getId(), IngestionStatus.FAILED.name(),
                        "IMPORT", 10, "INGESTION_NO_ASSETS",
                        "source has no ingestible assets", LocalDateTime.now());
                return;
            }

            // Create DRAFT revision BEFORE extraction so pages/blocks can own it.
            ExtractionRevision revision = extractionRevisionService.createForVerifiedSource(
                    job.getSpaceId(), job.getSourceId(), new ExtractionRevision());
            if (revision == null) {
                throw new IllegalStateException("failed to create extraction revision");
            }
            ingestionJobMapper.markStageComplete(jobId, "IMPORT", LocalDateTime.now());

            int processed = 0;
            int failed = 0;
            int total = assets.size();
            RuntimeException firstFailure = null;
            boolean anyLowConfidence = false;
            LocalDateTime lastBeat = LocalDateTime.now();

            for (SourceAsset asset : assets) {
                int progress = 30 + (int) (40.0 * processed / Math.max(total, 1));
                updateStatus(job, IngestionStatus.EXTRACTING.name(), "EXTRACT", progress);
                try {
                    boolean low = extractAsset(asset);
                    if (low) {
                        anyLowConfidence = true;
                    }
                } catch (Exception e) {
                    log.warn("asset {} extraction failed", asset.getId(), e);
                    if (firstFailure == null && typedFailureOf(e) != null
                            && e instanceof RuntimeException runtime) {
                        firstFailure = runtime;
                    }
                    failed++;
                }
                processed++;
                lastBeat = heartbeat(jobId, lastBeat);
            }

            if (failed == total) {
                // Nothing at all was extracted: reporting PARTIAL_FAILED with no
                // errorCode left the client unable to explain its own bad upload.
                throw firstFailure != null
                        ? firstFailure
                        : new IllegalStateException("all " + total + " assets failed to extract");
            }

            updateStatus(job, IngestionStatus.STRUCTURING.name(), "STRUCTURE", 70);
            LocalDateTime stampAt = LocalDateTime.now();
            revisionPublisher.publish(job.getSpaceId(), job.getSourceId(), revision.getId(), stampAt);
            ingestionIssueService.recordIfLowConfidence(
                    job.getSpaceId(), job.getSourceId(), job.getId(),
                    revision.getId(), anyLowConfidence, failed > 0);
            ingestionJobMapper.markStageComplete(jobId, "EXTRACT", LocalDateTime.now());

            if (failed > 0) {
                updateStatus(job, IngestionStatus.PARTIAL_FAILED.name(), "REVIEW", 100);
            } else {
                updateStatus(job, IngestionStatus.NEEDS_REVIEW.name(), "REVIEW", 100);
            }
        } catch (Exception e) {
            log.error("Ingestion job {} failed", jobId, e);
            IngestionFailure typed = typedFailureOf(e);
            String code = typed == null ? "INGESTION_FAILED" : typed.errorCode().name();
            String safe = typed == null ? genericSafeMessage(e) : typed.safeMessage();
            ingestionJobMapper.markTerminalFailure(jobId, IngestionStatus.FAILED.name(),
                    job.getStage(), job.getProgressPercent(), code, safe, LocalDateTime.now());
        }
    }

    /**
     * The pipeline wraps lower failures, so the typed one is often a cause
     * rather than the thrown exception.
     */
    private static IngestionFailure typedFailureOf(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof IngestionFailure failure) {
                return failure;
            }
            if (current == current.getCause()) {
                break;
            }
        }
        return null;
    }

    private static String genericSafeMessage(Throwable error) {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        return message.length() > 400 ? message.substring(0, 400) : message;
    }

    /**
     * Renews this worker's claim when the lease is running out. A multi-hundred
     * page OCR pass can otherwise outlive {@code stale-lease} and be requeued
     * while still running, putting two workers on the same source.
     */
    private LocalDateTime heartbeat(Long jobId, LocalDateTime lastBeat) {
        LocalDateTime now = LocalDateTime.now();
        if (lastBeat != null && java.time.Duration.between(lastBeat, now)
                .compareTo(workerProperties.getHeartbeat()) < 0) {
            return lastBeat;
        }
        ingestionJobMapper.refreshClaim(jobId, WORKER_ID, now);
        return now;
    }

    @Transactional
    public IngestionJob retry(String ownerSubject, Long spaceId, Long jobId) {
        IngestionJob existing = getMine(ownerSubject, spaceId, jobId);
        if (existing == null) {
            return null;
        }
        if (!IngestionStatus.FAILED.name().equals(existing.getStatus())
                && !IngestionStatus.PARTIAL_FAILED.name().equals(existing.getStatus())) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = ingestionJobMapper.requeueForStageRetry(
                jobId, spaceId, existing.getRetryCount() + 1, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(IngestionStatus.QUEUED.name());
        existing.setUpdatedAt(now);
        existing.setRetryCount(existing.getRetryCount() + 1);
        submit(jobId);
        return existing;
    }

    /**
     * ADMIN governance retry of any job row, without owner impersonation.
     *
     * <p>The governance screen used to issue its own {@code UPDATE ingestion_job
     * SET status='QUEUED'} and stop there, which left the row waiting for a
     * worker that only appears after a restart. Reset and dispatch are one
     * operation here, same as the owner-facing {@link #retry}.
     */
    @Transactional
    public IngestionJob adminRetry(Long jobId) {
        IngestionJob job = jobId == null ? null : ingestionJobMapper.selectById(jobId);
        if (job == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = ingestionJobMapper.requeueForAdminRetry(jobId, job.getSpaceId(), now);
        if (updated == 0) {
            // Terminal-for-this-operation state (NEEDS_REVIEW / PUBLISHED).
            return null;
        }
        submit(jobId);
        return ingestionJobMapper.selectById(jobId);
    }

    /**
     * Requeue abandoned claims and dispatch QUEUED jobs.
     *
     * <p>Runs at startup AND on a schedule: a job whose worker dies is only
     * reclaimable once its claim ages past the lease, so a startup-only sweep
     * would strand it until the next restart.
     */
    public int recoverStaleJobs() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minus(workerProperties.getStaleLease());
        int requeued = ingestionJobMapper.requeueStaleProcessing(now, staleBefore);
        List<IngestionJob> queued = ingestionJobMapper.selectQueued(50);
        if (queued != null) {
            for (IngestionJob job : queued) {
                submit(job.getId());
            }
        }
        return requeued + (queued == null ? 0 : queued.size());
    }

    private List<SourceAsset> discoverAssets(IngestionJob job) {
        List<SourceAsset> allAssets = sourceAssetMapper.selectBySpaceSource(
                job.getSpaceId(), job.getSourceId());
        if (allAssets == null || allAssets.isEmpty()) {
            return new ArrayList<>();
        }
        if (job.getAssetId() != null) {
            // The job was created for one asset; the rest of the source belongs to
            // other jobs and must not be re-extracted under this one.
            allAssets = allAssets.stream()
                    .filter(asset -> job.getAssetId().equals(asset.getId()))
                    .toList();
        }

        List<SourceAsset> toProcess = new ArrayList<>();
        List<SourceAsset> zipAssets = new ArrayList<>();

        for (SourceAsset asset : allAssets) {
            String ext = SourceAssetService.extractExtension(asset.getOriginalName());
            if ("zip".equalsIgnoreCase(ext)) {
                zipAssets.add(asset);
            } else {
                toProcess.add(asset);
            }
        }

        for (SourceAsset zipAsset : zipAssets) {
            try {
                List<SourceAsset> entries = extractZipEntries(zipAsset);
                toProcess.addAll(entries);
            } catch (Exception e) {
                // A typed pipeline failure already carries the code and the
                // client-safe text; re-wrapping it is what used to turn a blocked
                // zip-slip into a faceless INGESTION_FAILED.
                if (e instanceof IngestionFailure && e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                log.error("ZIP extraction failed for asset {}", zipAsset.getId(), e);
                throw new IllegalStateException("ZIP extraction failed for asset " + zipAsset.getId(), e);
            }
        }

        return toProcess;
    }

    private List<SourceAsset> extractZipEntries(SourceAsset zipAsset) throws IOException {
        try (InputStream in = storageService.load(zipAsset.getStorageKey())) {
            java.nio.file.Path temp = Files.createTempFile("aistudy-zip-extract-", ".zip");
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            ZipInspectionResult inspection = ZipArchiveInspector.inspectFile(
                    temp, ZipSafetyLimits.defaults());
            if (!inspection.valid()) {
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
                        StorageResult stored = storageService.store(entryIn, new StorageMetadata());
                        SourceAsset entryAsset = new SourceAsset();
                        entryAsset.setSpaceId(zipAsset.getSpaceId());
                        entryAsset.setSourceId(zipAsset.getSourceId());
                        entryAsset.setAssetRole(SourceAssetService.roleForExtension(ext));
                        entryAsset.setOriginalName(name);
                        entryAsset.setOriginalRelativePath(name);
                        entryAsset.setStorageKey(stored.storageKey());
                        entryAsset.setMimeType(SourceAssetService.mimeForExtension(ext));
                        entryAsset.setSizeBytes(stored.sizeBytes());
                        entryAsset.setSha256(stored.sha256());
                        entryAsset.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
                        sourceAssetMapper.insert(entryAsset);
                        entries.add(entryAsset);
                    }
                }
                return entries;
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** @return true when extraction reported low OCR/text confidence */
    private boolean extractAsset(SourceAsset asset) {
        String ext = SourceAssetService.extractExtension(asset.getOriginalName());
        if (ext == null) {
            return false;
        }
        return switch (ext.toLowerCase()) {
            case "pdf" -> pdfContentExtractionService.extractAndPersist(asset);
            case "jpg", "jpeg", "png", "webp" -> imageContentExtractionService.extractAndPersist(asset);
            case "docx" -> {
                MultipartFile multipart = multipartFromAsset(asset);
                docxContentParser.extract(multipart, asset.getSpaceId(), asset.getSourceId(), asset.getId());
                yield false;
            }
            case "txt", "md", "markdown" -> {
                MultipartFile multipart = multipartFromAsset(asset);
                textMarkdownContentParser.extract(multipart, asset.getSpaceId(), asset.getSourceId(), asset.getId());
                yield false;
            }
            default -> throw new IllegalStateException("unsupported asset extension: ." + ext);
        };
    }

    private MultipartFile multipartFromAsset(SourceAsset asset) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return asset.getOriginalName();
            }

            @Override
            public String getContentType() {
                return asset.getMimeType();
            }

            @Override
            public boolean isEmpty() {
                return asset.getSizeBytes() == null || asset.getSizeBytes() == 0;
            }

            @Override
            public long getSize() {
                return asset.getSizeBytes() != null ? asset.getSizeBytes() : 0;
            }

            @Override
            public byte[] getBytes() throws IOException {
                try (InputStream in = storageService.load(asset.getStorageKey())) {
                    return in.readAllBytes();
                }
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return storageService.load(asset.getStorageKey());
            }

            @Override
            public void transferTo(File dest) throws IOException {
                try (InputStream in = storageService.load(asset.getStorageKey());
                     OutputStream out = new FileOutputStream(dest)) {
                    in.transferTo(out);
                }
            }
        };
    }

    /**
     * Progress write for one stage transition. Deliberately NOT annotated
     * {@code @Transactional}: it is called from {@code processJob} inside this
     * same bean, where the proxy never sees the call, and each transition is a
     * single UPDATE that autocommits on its own. Multi-statement atomicity
     * belongs to {@link IngestionRevisionPublisher}.
     */
    private void updateStatus(IngestionJob job, String status, String stage, int progress) {
        job.setStatus(status);
        job.setStage(stage);
        job.setProgressPercent(progress);
        job.setUpdatedAt(LocalDateTime.now());
        ingestionJobMapper.updateById(job);
    }
}
