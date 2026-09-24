package com.aistudy.server.mastery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-014 — Mastery persistence model (V019).
 *
 * <p>Current capability state of (user, space, knowledge point);
 * recomputed by {@code MasteryService} from evidence. Unique
 * (user_subject, space_id, knowledge_point_id).
 */
@TableName("mastery")
public class Mastery {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userSubject;

    private Long spaceId;

    private Long knowledgePointId;

    private Double masteryScore;

    private Double confidence;

    private String algorithmVersion;

    private Integer practiceEvidenceCount;

    private Integer examEvidenceCount;

    private Integer reviewEvidenceCount;

    private LocalDateTime lastEvidenceAt;

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

    public Long getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }

    public Double getMasteryScore() {
        return masteryScore;
    }

    public void setMasteryScore(Double masteryScore) {
        this.masteryScore = masteryScore;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public void setAlgorithmVersion(String algorithmVersion) {
        this.algorithmVersion = algorithmVersion;
    }

    public Integer getPracticeEvidenceCount() {
        return practiceEvidenceCount;
    }

    public void setPracticeEvidenceCount(Integer practiceEvidenceCount) {
        this.practiceEvidenceCount = practiceEvidenceCount;
    }

    public Integer getExamEvidenceCount() {
        return examEvidenceCount;
    }

    public void setExamEvidenceCount(Integer examEvidenceCount) {
        this.examEvidenceCount = examEvidenceCount;
    }

    public Integer getReviewEvidenceCount() {
        return reviewEvidenceCount;
    }

    public void setReviewEvidenceCount(Integer reviewEvidenceCount) {
        this.reviewEvidenceCount = reviewEvidenceCount;
    }

    public LocalDateTime getLastEvidenceAt() {
        return lastEvidenceAt;
    }

    public void setLastEvidenceAt(LocalDateTime lastEvidenceAt) {
        this.lastEvidenceAt = lastEvidenceAt;
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
}
