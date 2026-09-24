package com.aistudy.server.ingestion.revision.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("extraction_revision")
public class ExtractionRevision {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;
    private Long sourceId;
    private Integer version;
    private String status;
    private String extractorVersion;
    private String ocrEngine;
    private String ocrModel;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getExtractorVersion() { return extractorVersion; }
    public void setExtractorVersion(String extractorVersion) { this.extractorVersion = extractorVersion; }
    public String getOcrEngine() { return ocrEngine; }
    public void setOcrEngine(String ocrEngine) { this.ocrEngine = ocrEngine; }
    public String getOcrModel() { return ocrModel; }
    public void setOcrModel(String ocrModel) { this.ocrModel = ocrModel; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
