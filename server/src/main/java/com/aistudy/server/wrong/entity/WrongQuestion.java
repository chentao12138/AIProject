package com.aistudy.server.wrong.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-011 — WrongQuestion persistence model (V016).
 *
 * <p>One aggregate row per (user_subject, space_id, question_id):
 * wrong answers increment {@code wrongCount} and refresh
 * {@code lastWrongAt}; a correct review records {@code lastCorrectAt}
 * and advances the status (ACTIVE → IMPROVING → MASTERED, policy in
 * {@code ReviewSchedulePolicy}). DISMISSED is reserved (no API).
 */
@TableName("wrong_question")
public class WrongQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userSubject;

    private Long spaceId;

    private Long questionId;

    private LocalDateTime firstWrongAt;

    private LocalDateTime lastWrongAt;

    private Integer wrongCount;

    private LocalDateTime lastCorrectAt;

    private String status;

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

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public LocalDateTime getFirstWrongAt() {
        return firstWrongAt;
    }

    public void setFirstWrongAt(LocalDateTime firstWrongAt) {
        this.firstWrongAt = firstWrongAt;
    }

    public LocalDateTime getLastWrongAt() {
        return lastWrongAt;
    }

    public void setLastWrongAt(LocalDateTime lastWrongAt) {
        this.lastWrongAt = lastWrongAt;
    }

    public Integer getWrongCount() {
        return wrongCount;
    }

    public void setWrongCount(Integer wrongCount) {
        this.wrongCount = wrongCount;
    }

    public LocalDateTime getLastCorrectAt() {
        return lastCorrectAt;
    }

    public void setLastCorrectAt(LocalDateTime lastCorrectAt) {
        this.lastCorrectAt = lastCorrectAt;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
