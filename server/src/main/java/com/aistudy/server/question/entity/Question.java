package com.aistudy.server.question.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-008 — Question persistence model (V013).
 *
 * <p>Question belongs to one LearningSpace. Ownership derives from
 * space_id -&gt; learning_space.owner_subject; every business read
 * joins learning_space and constrains owner_subject.
 *
 * <p>{@code answerDataJson} is a server-written compact JSON string
 * carrying the type-specific correct answer. It is NEVER returned to
 * practice/exam clients before submit (api-guidelines.md §9); only
 * authoring responses may expose it.
 *
 * <p>This slice produces USER_CURATED DRAFT questions only;
 * {@code DRAFT -&gt; PUBLISHED} via the publish action (idempotent).
 * {@code deletedAt != null} = soft-deleted; reads filter
 * {@code deleted_at IS NULL}.
 */
@TableName("question")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private String questionType;

    private String stem;

    private String answerDataJson;

    private String explanation;

    private String difficulty;

    private String originType;

    private String status;

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

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public String getStem() {
        return stem;
    }

    public void setStem(String stem) {
        this.stem = stem;
    }

    public String getAnswerDataJson() {
        return answerDataJson;
    }

    public void setAnswerDataJson(String answerDataJson) {
        this.answerDataJson = answerDataJson;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
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
        return "Question{id=" + id
                + ", spaceId=" + spaceId
                + ", questionType='" + questionType + '\''
                + ", status='" + status + '\''
                + ", publishedAt=" + publishedAt
                + '}';
    }
}
