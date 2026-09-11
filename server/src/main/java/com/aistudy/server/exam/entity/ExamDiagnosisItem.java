package com.aistudy.server.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-015 — ExamDiagnosisItem persistence model (V020).
 *
 * <p>One dimension aggregate of a diagnosis:
 * {@code KNOWLEDGE_POINT} (dimension_id = kp id, label = kp title) or
 * {@code QUESTION_TYPE} (dimension_id = NULL, label = type name).
 * score/maxScore/accuracy/evidenceCount are derived from the
 * submitted graded items; severity/recommendation stay NULL in V1.
 */
@TableName("exam_diagnosis_item")
public class ExamDiagnosisItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examDiagnosisId;

    private String dimensionType;

    private Long dimensionId;

    private String label;

    private Integer score;

    private Integer maxScore;

    private Double accuracy;

    private Integer evidenceCount;

    private String severity;

    private String recommendation;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getExamDiagnosisId() {
        return examDiagnosisId;
    }

    public void setExamDiagnosisId(Long examDiagnosisId) {
        this.examDiagnosisId = examDiagnosisId;
    }

    public String getDimensionType() {
        return dimensionType;
    }

    public void setDimensionType(String dimensionType) {
        this.dimensionType = dimensionType;
    }

    public Long getDimensionId() {
        return dimensionId;
    }

    public void setDimensionId(Long dimensionId) {
        this.dimensionId = dimensionId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
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

    public Double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(Double accuracy) {
        this.accuracy = accuracy;
    }

    public Integer getEvidenceCount() {
        return evidenceCount;
    }

    public void setEvidenceCount(Integer evidenceCount) {
        this.evidenceCount = evidenceCount;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
