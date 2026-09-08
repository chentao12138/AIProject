package com.aistudy.server.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-013 — ExamAnswer persistence model (V018).
 *
 * <p>Latest answer per attempt slot, graded by the shared evaluator
 * against the frozen paper snapshot. {@code gradingStatus}: GRADED
 * (objective) | UNGRADED (SHORT_ANSWER). Correctness is NEVER
 * returned before submit (api-guidelines.md §9).
 */
@TableName("exam_answer")
public class ExamAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examAttemptId;

    private Long examQuestionId;

    private Long spaceId;

    private String answerDataJson;

    private Integer score;

    private Boolean isCorrect;

    private LocalDateTime answeredAt;

    private String gradingStatus;

    private String feedback;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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

    public Long getExamQuestionId() {
        return examQuestionId;
    }

    public void setExamQuestionId(Long examQuestionId) {
        this.examQuestionId = examQuestionId;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public String getAnswerDataJson() {
        return answerDataJson;
    }

    public void setAnswerDataJson(String answerDataJson) {
        this.answerDataJson = answerDataJson;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public Boolean getIsCorrect() {
        return isCorrect;
    }

    public void setIsCorrect(Boolean isCorrect) {
        this.isCorrect = isCorrect;
    }

    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(LocalDateTime answeredAt) {
        this.answeredAt = answeredAt;
    }

    public String getGradingStatus() {
        return gradingStatus;
    }

    public void setGradingStatus(String gradingStatus) {
        this.gradingStatus = gradingStatus;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
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
