package com.aistudy.server.question.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-008 — QuestionKnowledgePoint persistence model (V013).
 *
 * <p>Explicit M:N between Question and KnowledgePoint
 * (docs/data-model.md §10.3). A question may test several points; a
 * point may be covered by several questions. Same-space invariant is
 * enforced by the service; {@code weight} stays NULL in V1.
 */
@TableName("question_knowledge_point")
public class QuestionKnowledgePoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long questionId;

    private Long knowledgePointId;

    private Integer weight;

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

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
