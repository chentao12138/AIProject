package com.aistudy.server.source.outline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("source_outline_node")
public class SourceOutlineNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;
    private Long sourceId;
    private Long parentId;
    /** Extraction revision this outline node belongs to (V053). */
    private Long extractionRevisionId;
    private String nodeType;
    private String title;
    private String numberLabel;
    private Integer sortOrder;
    private Integer startPage;
    private Integer endPage;
    private String status;
    private Double confidence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Long getExtractionRevisionId() { return extractionRevisionId; }
    public void setExtractionRevisionId(Long extractionRevisionId) { this.extractionRevisionId = extractionRevisionId; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getNumberLabel() { return numberLabel; }
    public void setNumberLabel(String numberLabel) { this.numberLabel = numberLabel; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Integer getStartPage() { return startPage; }
    public void setStartPage(Integer startPage) { this.startPage = startPage; }
    public Integer getEndPage() { return endPage; }
    public void setEndPage(Integer endPage) { this.endPage = endPage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
