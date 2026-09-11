package com.aistudy.server.practice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-010 — PracticeAnswer persistence model (V015).
 *
 * <p>The latest answer of one practice slot, graded server-side for
 * objective types by {@code QuestionAnswerEvaluator} against the
 * slot's frozen snapshot. SHORT_ANSWER stays ungraded (isCorrect /
 * score NULL) — no AI/human grading in V1.
 */
@TableName("practice_answer")
public class PracticeAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userSubject;

    private Long spaceId;

    private Long practiceSessionQuestionId;

    private Long questionId;

    private String answerDataJson;

    private Boolean isCorrect;

    private Integer score;

    private LocalDateTime submittedAt;

    private Integer durationMs;

    private String feedbackJson;

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

    public Long getPracticeSessionQuestionId() {
        return practiceSessionQuestionId;
    }

    public void setPracticeSessionQuestionId(Long practiceSessionQuestionId) {
        this.practiceSessionQuestionId = practiceSessionQuestionId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getAnswerDataJson() {
        return answerDataJson;
    }

    public void setAnswerDataJson(String answerDataJson) {
        this.answerDataJson = answerDataJson;
    }

    public Boolean getIsCorrect() {
        return isCorrect;
    }

    public void setIsCorrect(Boolean isCorrect) {
        this.isCorrect = isCorrect;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getFeedbackJson() {
        return feedbackJson;
    }

    public void setFeedbackJson(String feedbackJson) {
        this.feedbackJson = feedbackJson;
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
