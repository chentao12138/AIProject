package com.aistudy.server.ingestion.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-005 — production IngestionJob persistence model.
 *
 * <p>Backs the {@code ingestion_job} table (V009 migration). One row
 * = one ingestion attempt for a Source (SourceDocument metadata,
 * data-model.md §7.1) and, in V1, exactly one SourceAsset (the file
 * being ingested). A job is process state, not business content: it
 * records status/stage/progress and a SAFE error diagnostic, and may
 * be cleaned up historically later.
 *
 * <h3>Lifecycle (service-enforced, BACKEND_AUTORUN_4H.md §5.3)</h3>
 *
 * <pre>
 * PENDING --start--> RUNNING --finish--> SUCCEEDED
 *    |                   |
 *    +--fail--> FAILED --+--fail--> FAILED
 *                  |
 *                  +--retry--> PENDING (retryCount++)
 * </pre>
 *
 * {@code stage} tracks the pipeline position
 * (QUEUED/IMPORTING/EXTRACTING/STRUCTURING/AI_PROCESSING/
 * NEEDS_REVIEW/PUBLISHED, content-ingestion.md §6); {@code status}
 * is the coarse lifecycle state. PARTIAL_FAILED is deferred — V1
 * ingestion is all-or-nothing per asset.
 *
 * <h3>Ownership model</h3>
 *
 * <p>Deliberately NO {@code ownerSubject} column: ownership derives
 * from the parent chain {@code ingestion_job -> source_asset ->
 * source -> learning_space} (source/asset may be read through the
 * owner-scoped services; every job read is an owner-scoped SQL JOIN).
 * {@code createdByUserId} is audit metadata only (cf. V005 source).
 *
 * <h3>MyBatis-Plus mapping</h3>
 *
 * <p>{@code @TableName("ingestion_job")} + {@code @TableId(type =
 * IdType.AUTO)} follow the project convention; camelCase fields map
 * to snake_case columns via {@code map-underscore-to-camel-case}.
 * No business rules live here — they are in
 * {@link com.aistudy.server.ingestion.job.service.IngestionJobService}.
 */
@TableName("ingestion_job")
public class IngestionJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long sourceId;

    private Long assetId;

    private String status;

    private String stage;

    private Integer progressPercent;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Integer retryCount;

    private String errorCode;

    private String errorMessage;

    private String createdByUserId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Long getAssetId() {
        return assetId;
    }

    public void setAssetId(Long assetId) {
        this.assetId = assetId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public Integer getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(Integer progressPercent) {
        this.progressPercent = progressPercent;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "IngestionJob{id=" + id
                + ", spaceId=" + spaceId
                + ", sourceId=" + sourceId
                + ", assetId=" + assetId
                + ", status='" + status + '\''
                + ", stage='" + stage + '\''
                + ", progressPercent=" + progressPercent
                + ", retryCount=" + retryCount
                + ", errorCode='" + errorCode + '\''
                + ", createdAt=" + createdAt
                + '}';
    }
}
