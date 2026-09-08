package com.aistudy.server.practice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-009 — PracticeSessionQuestion persistence model (V014).
 *
 * <p>One fixed slot of a practice session: the live question id plus
 * an immutable {@code questionSnapshotJson} (stem/options/answerData
 * at creation). Grading (010) reads ONLY the snapshot, so later edits
 * of the live question cannot change what this session asked or how
 * answers are scored. Redundant {@code spaceId} keeps reads and test
 * cleanup space-scoped.
 */
@TableName("practice_session_question")
public class PracticeSessionQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long practiceSessionId;

    private Long questionId;

    private Integer sortOrder;

    private String questionSnapshotJson;

    private LocalDateTime createdAt;

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

    public Long getPracticeSessionId() {
        return practiceSessionId;
    }

    public void setPracticeSessionId(Long practiceSessionId) {
        this.practiceSessionId = practiceSessionId;
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
