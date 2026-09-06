package com.aistudy.server.ingestion.job.dto;

import com.aistudy.server.ingestion.job.entity.IngestionJob;

import java.time.LocalDateTime;

/**
 * BUSINESS-005 — typed IngestionJob response (api-guidelines.md §6:
 * stage/status, progress, safe error).
 *
 * <p>Deliberately exposes NO stack trace and no server-internal
 * details — {@code errorMessage} is the bounded SAFE diagnostic
 * stored on the job (content-ingestion.md §6). {@code createdByUserId}
 * is audit metadata and not echoed, consistent with
 * LearningSpaceResponse/SourceResponse.
 *
 * @param id              job id
 * @param spaceId         parent space id
 * @param sourceId        parent source id
 * @param assetId         ingested asset id (null only for future non-asset jobs)
 * @param status          PENDING | RUNNING | SUCCEEDED | FAILED
 * @param stage           QUEUED | IMPORTING | EXTRACTING | STRUCTURING |
 *                        AI_PROCESSING | NEEDS_REVIEW | PUBLISHED
 * @param progressPercent 0..100
 * @param startedAt       when the job left PENDING (null until then)
 * @param finishedAt      when the job reached SUCCEEDED/FAILED (null until then)
 * @param retryCount      number of retries performed
 * @param errorCode       stable machine-readable code (null when not failed)
 * @param errorMessage    SAFE diagnostic (null when not failed)
 * @param createdAt       creation timestamp
 * @param updatedAt       last state-change timestamp
 */
public record IngestionJobResponse(
        Long id,
        Long spaceId,
        Long sourceId,
        Long assetId,
        String status,
        String stage,
        Integer progressPercent,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Integer retryCount,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static IngestionJobResponse from(IngestionJob job) {
        return new IngestionJobResponse(
                job.getId(),
                job.getSpaceId(),
                job.getSourceId(),
                job.getAssetId(),
                job.getStatus(),
                job.getStage(),
                job.getProgressPercent(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getRetryCount(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
