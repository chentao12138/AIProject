package com.aistudy.server.knowledge.source.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-007 — production KnowledgePointSource persistence model
 * (provenance, docs/data-model.md §8.2, R-KNOW-002, ADR-039).
 *
 * <p>Backs the {@code knowledge_point_source} table (V012 migration).
 * One row = one provenance link between a KnowledgePoint and a
 * ContentBlock it was derived from (M:N — a point may cite many
 * blocks, a block may be cited by many points).
 *
 * <h3>Same-space invariant (CRITICAL)</h3>
 *
 * <p>{@code spaceId} is stored explicitly because an ordinary FK
 * cannot prove that the knowledge_point and the content_block belong
 * to the SAME LearningSpace (runbook §7.2). The service validates
 * BOTH endpoints with owner-scoped reads against the path spaceId
 * BEFORE inserting; every read is an owner-scoped JOIN. A link can
 * never cross spaces.
 *
 * <p>{@code relationType} / {@code relevanceScore} are documented
 * optional fields with no producer in V1 (NULL); {@code createdByUserId}
 * is audit metadata, not an authorization key.
 *
 * <h3>MyBatis-Plus mapping</h3>
 *
 * <p>{@code @TableName("knowledge_point_source")} + {@code @TableId(type =
 * IdType.AUTO)} follow the project convention; camelCase fields map
 * to snake_case columns via {@code map-underscore-to-camel-case}.
 * No business rules live here — they are in
 * {@link com.aistudy.server.knowledge.source.service.KnowledgePointSourceService}.
 */
@TableName("knowledge_point_source")
public class KnowledgePointSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long knowledgePointId;

    private Long contentBlockId;

    private String relationType;

    private Double relevanceScore;

    private String createdByUserId;

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

    public Long getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }

    public Long getContentBlockId() {
        return contentBlockId;
    }

    public void setContentBlockId(Long contentBlockId) {
        this.contentBlockId = contentBlockId;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public Double getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(Double relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "KnowledgePointSource{id=" + id
                + ", spaceId=" + spaceId
                + ", knowledgePointId=" + knowledgePointId
                + ", contentBlockId=" + contentBlockId
                + ", createdAt=" + createdAt
                + '}';
    }
}
