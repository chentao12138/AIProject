package com.aistudy.server.knowledge.point.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-003 — production KnowledgePoint persistence model.
 *
 * <p>Backs the {@code knowledge_point} table (V007). KnowledgePoint
 * is the core learning unit (docs/data-model.md §8.1): it belongs to
 * one LearningSpace, optionally to one KnowledgeCategory inside that
 * space, and carries the user's knowledge content.
 *
 * <h3>This slice: USER_CURATED manual lifecycle</h3>
 *
 * <p>{@code originType} is fixed to {@code USER_CURATED} by the
 * service on create; {@code status} moves {@code DRAFT} →
 * {@code PUBLISHED} via the publish action. SOURCE_DERIVED /
 * AI_DERIVED / ADMIN_CURATED are documented values but are NOT
 * produced here (they need ContentBlock/ingestion and a formal Admin
 * API). ARCHIVED / REJECTED / PROCESSING / NEEDS_REVIEW are also out
 * of scope.
 *
 * <h3>Soft delete</h3>
 *
 * <p>{@code deletedAt != null} marks a soft-deleted row. ALL business
 * reads filter {@code deleted_at IS NULL} (see the mapper Javadoc);
 * there is no delete API this round, the column exists for the future.
 *
 * <h3>Ownership</h3>
 *
 * <p>No owner column: ownership derives from the parent LearningSpace
 * via {@code spaceId}. Every read joins {@code learning_space} and
 * constrains {@code ls.owner_subject}. The "category belongs to the
 * same space" invariant is enforced by the service with scoped SQL.
 *
 * <p>{@code createdByUserId} is audit metadata (who created it), NOT
 * the authorization key and NOT returned in API responses.
 */
@TableName("knowledge_point")
public class KnowledgePoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long categoryId;

    private String title;

    private String summary;

    private String content;

    private String originType;

    private String status;

    private String difficulty;

    private String createdByUserId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime publishedAt;

    private LocalDateTime archivedAt;
    private LocalDateTime rejectedAt;
    private String rejectedReason;

    private LocalDateTime deletedAt;

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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOriginType() {
        return originType;
    }

    public void setOriginType(String originType) {
        this.originType = originType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
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

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    public LocalDateTime getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(LocalDateTime rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public String getRejectedReason() {
        return rejectedReason;
    }

    public void setRejectedReason(String rejectedReason) {
        this.rejectedReason = rejectedReason;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    @Override
    public String toString() {
        return "KnowledgePoint{id=" + id
                + ", spaceId=" + spaceId
                + ", categoryId=" + categoryId
                + ", title='" + title + '\''
                + ", originType='" + originType + '\''
                + ", status='" + status + '\''
                + ", publishedAt=" + publishedAt
                + '}';
    }
}
