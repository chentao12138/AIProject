package com.aistudy.server.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-015 — ExamDiagnosis persistence model (V020).
 *
 * <p>One structured diagnosis per SUBMITTED exam attempt (unique per
 * attempt). Generated deterministically at submit time from the
 * paper's frozen snapshot + graded answers — never client-submitted,
 * never AI. Detail rows live in {@link ExamDiagnosisItem}.
 */
@TableName("exam_diagnosis")
public class ExamDiagnosis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examAttemptId;

    private String userSubject;

    private Long spaceId;

    private String summary;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getExamAttemptId() {
        return examAttemptId;
    }

    public void setExamAttemptId(Long examAttemptId) {
        this.examAttemptId = examAttemptId;
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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
