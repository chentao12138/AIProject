package com.aistudy.server.source.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-002 — production Source persistence model.
 *
 * <p>Backs the {@code source} table (V005 migration). A Source is a
 * registered learning material (SourceDocument metadata per
 * docs/data-model.md §5.1) inside a LearningSpace. This vertical
 * slice stores METADATA only — there is no binary upload, no file
 * parsing, no ingestion pipeline.
 *
 * <h3>Ownership model</h3>
 *
 * <p>There is deliberately NO {@code ownerSubject} column on this
 * entity: a Source's ownership is derived from its parent
 * LearningSpace through {@code spaceId}. Every read goes through
 * SQL that joins {@code learning_space} and constrains
 * {@code ls.owner_subject = ?}, so a Source can never contradict
 * its parent's owner and cannot be read across spaces/owners.
 *
 * <p>{@link #createdByUserId} is audit metadata (who registered the
 * source), NOT the authorization key.
 *
 * <h3>MyBatis-Plus mapping</h3>
 *
 * <p>{@code @TableName("source")} + {@code @TableId(type = IdType.AUTO)}
 * follow the established project convention (SpikeRecord,
 * LearningSpace). camelCase fields map to snake_case columns via the
 * global {@code map-underscore-to-camel-case: true} setting.
 *
 * <p>This class carries no business rules — rules (parent ownership
 * validation, status defaulting, timestamp maintenance) live in
 * {@link com.aistudy.server.source.service.SourceService}.
 */
@TableName("source")
public class Source {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private String title;

    private String sourceType;

    private String status;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
        return "Source{id=" + id
                + ", spaceId=" + spaceId
                + ", title='" + title + '\''
                + ", sourceType='" + sourceType + '\''
                + ", status='" + status + '\''
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + '}';
    }
}
