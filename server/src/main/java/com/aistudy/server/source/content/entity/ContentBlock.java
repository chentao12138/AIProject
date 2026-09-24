package com.aistudy.server.source.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-006 — production ContentBlock persistence model (EXTRACTED
 * layer, docs/data-model.md §6.3, R-INGEST-008).
 *
 * <p>Backs the {@code content_block} table (V011 migration). One row
 * = the smallest referenceable unit of structured body text:
 * HEADING / PARAGRAPH / LIST / TABLE / FIGURE / CODE / FORMULA /
 * OTHER. V1 (TXT/Markdown) produces HEADING / PARAGRAPH / LIST /
 * TABLE / CODE deterministically with no AI; blocks carry
 * {@code sortOrder} (document order) and {@code locatorJson}
 * ({@code {"lineStart":N,"lineEnd":M}}, 1-based, normalized text) so
 * future KnowledgePoint provenance can cite an exact block range.
 *
 * <h3>Ownership model</h3>
 *
 * <p>Deliberately NO {@code ownerSubject} column: ownership derives
 * from the parent chain {@code content_block -> source_page ->
 * source -> learning_space}. Every read is an owner-scoped SQL JOIN
 * and writes validate source+asset via the owner-scoped services
 * first (BUSINESS-004 D1/D2 pattern).
 *
 * <h3>MyBatis-Plus mapping</h3>
 *
 * <p>{@code @TableName("content_block")} + {@code @TableId(type =
 * IdType.AUTO)} follow the project convention; camelCase fields map
 * to snake_case columns via {@code map-underscore-to-camel-case}.
 * {@code structuredDataJson} / {@code locatorJson} are TEXT columns
 * mapped as plain String (server-side opaque JSON in V1).
 * No business rules live here — extraction writes are performed by
 * {@link com.aistudy.server.ingestion.extract.ContentExtractionService},
 * reads by {@link com.aistudy.server.source.content.service.ContentBlockService}.
 */
@TableName("content_block")
public class ContentBlock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long sourceId;

    /** Extraction revision this block belongs to (V053). */
    private Long extractionRevisionId;

    public Long getExtractionRevisionId() {
        return extractionRevisionId;
    }

    public void setExtractionRevisionId(Long extractionRevisionId) {
        this.extractionRevisionId = extractionRevisionId;
    }

    private Long sourcePageId;

    private Long sourceOutlineNodeId;

    private String blockType;

    private Integer sortOrder;

    private String normalizedText;

    private String structuredDataJson;

    private String locatorJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Long getSourcePageId() {
        return sourcePageId;
    }

    public void setSourcePageId(Long sourcePageId) {
        this.sourcePageId = sourcePageId;
    }

    public Long getSourceOutlineNodeId() {
        return sourceOutlineNodeId;
    }

    public void setSourceOutlineNodeId(Long sourceOutlineNodeId) {
        this.sourceOutlineNodeId = sourceOutlineNodeId;
    }

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public void setNormalizedText(String normalizedText) {
        this.normalizedText = normalizedText;
    }

    public String getStructuredDataJson() {
        return structuredDataJson;
    }

    public void setStructuredDataJson(String structuredDataJson) {
        this.structuredDataJson = structuredDataJson;
    }

    public String getLocatorJson() {
        return locatorJson;
    }

    public void setLocatorJson(String locatorJson) {
        this.locatorJson = locatorJson;
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

    @Override
    public String toString() {
        return "ContentBlock{id=" + id
                + ", spaceId=" + spaceId
                + ", sourceId=" + sourceId
                + ", sourcePageId=" + sourcePageId
                + ", blockType='" + blockType + '\''
                + ", sortOrder=" + sortOrder
                + ", createdAt=" + createdAt
                + '}';
    }
}
