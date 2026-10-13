package com.aistudy.server.practice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-009 — PracticeSession persistence model (V014).
 *
 * <p>A user's practice run inside one LearningSpace. Identity =
 * {@code user_subject} (JWT sub) + {@code spaceId}; every read is
 * scoped on both. Lifecycle: {@code CREATED → IN_PROGRESS →
 * SUBMITTED} (service-enforced, invalid transitions → 409).
 */
@TableName("practice_session")
public class PracticeSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userSubject;

    private Long spaceId;

    private String status;

    private String scopeJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /**
     * Objective score recorded once at {@code finish}. Nullable: a session
     * submitted before V066 has no stored result, and the result endpoint
     * says so instead of reporting 0.
     */
    private Integer score;

    /** Maximum achievable objective score of the same submission. */
    private Integer maxScore;

    /** How many slots carried an answer at submission time. */
    private Integer answeredCount;

    /** How many answered objective slots were correct. */
    private Integer correctCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserSubject() {
        return userSubject;
    }

    public void setUserSubject(String userSubject) {
        this.userSubject = userSubject;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getScopeJson() {
        return scopeJson;
    }

    public void setScopeJson(String scopeJson) {
        this.scopeJson = scopeJson;
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

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public Integer getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(Integer maxScore) {
        this.maxScore = maxScore;
    }

    public Integer getAnsweredCount() {
        return answeredCount;
    }

    public void setAnsweredCount(Integer answeredCount) {
        this.answeredCount = answeredCount;
    }

    public Integer getCorrectCount() {
        return correctCount;
    }

    public void setCorrectCount(Integer correctCount) {
        this.correctCount = correctCount;
    }

    @Override
    public String toString() {
        return "PracticeSession{id=" + id
                + ", userSubject='" + userSubject + '\''
                + ", spaceId=" + spaceId
                + ", status='" + status + '\''
                + '}';
    }
}
