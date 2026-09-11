package com.aistudy.server.source.page.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-006 — production SourcePage persistence model (EXTRACTED
 * layer, docs/data-model.md §6.1).
 *
 * <p>Backs the {@code source_page} table (V010 migration). One row =
 * one independently orderable/referenceable page of a Source. V1
 * (TXT/Markdown ingestion) creates exactly ONE page per ingested text
 * asset ({@code pageOrder=1}, {@code pageType=BODY},
 * {@code orderStatus=AUTO}, {@code extractedText}=full decoded text);
 * every ContentBlock of the asset anchors to it
 * (SourceDocument → SourcePage → ContentBlock provenance chain,
 * data-model.md §13).
 *
 * <h3>Ownership model</h3>
 *
 * <p>Deliberately NO {@code ownerSubject} column: ownership derives
 * from the parent chain {@code source_page -> source_asset -> source
 * -> learning_space}. Every read is an owner-scoped SQL JOIN and
 * writes validate source+asset via the owner-scoped services first
 * (BUSINESS-004 D1/D2 pattern).
 *
 * <h3>MyBatis-Plus mapping</h3>
 *
 * <p>{@code @TableName("source_page")} + {@code @TableId(type =
 * IdType.AUTO)} follow the project convention; camelCase fields map
 * to snake_case columns via {@code map-underscore-to-camel-case}.
 * No business rules live here — extraction writes are performed by
 * {@link com.aistudy.server.ingestion.extract.ContentExtractionService},
 * reads by {@link com.aistudy.server.source.page.service.SourcePageService}.
 */
@TableName("source_page")
public class SourcePage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long sourceId;

    private Long sourceAssetId;

    private Integer sourcePageNumber;

    private Integer pageOrder;

    private Integer printedPageNumber;

    private String pageType;

    private Double orderConfidence;

    private String orderStatus;

    private String extractedText;

    private Double extractionConfidence;

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

    public Long getSourceAssetId() {
        return sourceAssetId;
    }

    public void setSourceAssetId(Long sourceAssetId) {
        this.sourceAssetId = sourceAssetId;
    }

    public Integer getSourcePageNumber() {
        return sourcePageNumber;
    }

    public void setSourcePageNumber(Integer sourcePageNumber) {
        this.sourcePageNumber = sourcePageNumber;
    }

    public Integer getPageOrder() {
        return pageOrder;
    }

    public void setPageOrder(Integer pageOrder) {
        this.pageOrder = pageOrder;
    }

    public Integer getPrintedPageNumber() {
        return printedPageNumber;
    }

    public void setPrintedPageNumber(Integer printedPageNumber) {
        this.printedPageNumber = printedPageNumber;
    }

    public String getPageType() {
        return pageType;
    }

    public void setPageType(String pageType) {
        this.pageType = pageType;
    }

    public Double getOrderConfidence() {
        return orderConfidence;
    }

    public void setOrderConfidence(Double orderConfidence) {
        this.orderConfidence = orderConfidence;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public Double getExtractionConfidence() {
        return extractionConfidence;
    }

    public void setExtractionConfidence(Double extractionConfidence) {
        this.extractionConfidence = extractionConfidence;
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
        return "SourcePage{id=" + id
                + ", spaceId=" + spaceId
                + ", sourceId=" + sourceId
                + ", pageOrder=" + pageOrder
                + ", pageType='" + pageType + '\''
                + ", orderStatus='" + orderStatus + '\''
                + ", createdAt=" + createdAt
                + '}';
    }
}
