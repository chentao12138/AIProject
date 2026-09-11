package com.aistudy.server.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-012 — ExamQuestion persistence model (V017).
 *
 * <p>One scored question slot of a paper. The frozen
 * {@code questionSnapshotJson} (same shape as the practice snapshot:
 * questionType/stem/explanation/options/answerData) is the grading
 * truth for exam attempts — editing the live question after publish
 * can never change a historical paper.
 */
@TableName("exam_question")
public class ExamQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examPaperId;

    private Long spaceId;

    private Long questionId;

    private Integer sortOrder;

    private Integer score;

    private String questionSnapshotJson;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getExamPaperId() {
        return examPaperId;
    }

    public void setExamPaperId(Long examPaperId) {
        this.examPaperId = examPaperId;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getQuestionSnapshotJson() {
        return questionSnapshotJson;
    }

    public void setQuestionSnapshotJson(String questionSnapshotJson) {
        this.questionSnapshotJson = questionSnapshotJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
