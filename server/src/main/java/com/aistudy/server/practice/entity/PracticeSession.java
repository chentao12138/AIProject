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

    @Override
    public String toString() {
        return "PracticeSession{id=" + id
                + ", userSubject='" + userSubject + '\''
                + ", spaceId=" + spaceId
                + ", status='" + status + '\''
                + '}';
    }
}
