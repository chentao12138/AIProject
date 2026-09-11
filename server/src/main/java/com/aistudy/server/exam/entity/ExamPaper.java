package com.aistudy.server.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-012 — ExamPaper persistence model (V017).
 *
 * <p>A fixed paper VERSION of an exam, created at publish time
 * (V1: version 1, status PUBLISHED). ExamAttempt (013) references the
 * paper, never the live exam composition.
 */
@TableName("exam_paper")
public class ExamPaper {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long examId;

    private Integer paperVersion;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;

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

    public Long getExamId() {
        return examId;
    }

    public void setExamId(Long examId) {
        this.examId = examId;
    }

    public Integer getPaperVersion() {
        return paperVersion;
    }

    public void setPaperVersion(Integer paperVersion) {
        this.paperVersion = paperVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
