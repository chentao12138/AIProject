package com.aistudy.server.ingestion.issue.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ingestion_issue")
public class IngestionIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;
    private Long sourceId;
    private Long ingestionJobId;
    private Long sourcePageId;
    private Long extractionRevisionId;
    private String issueType;
    private String severity;
    private String message;
    private String safeMessage;
    private String status;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getIngestionJobId() { return ingestionJobId; }
    public void setIngestionJobId(Long ingestionJobId) { this.ingestionJobId = ingestionJobId; }
    public Long getSourcePageId() { return sourcePageId; }
    public void setSourcePageId(Long sourcePageId) { this.sourcePageId = sourcePageId; }
    public Long getExtractionRevisionId() { return extractionRevisionId; }
    public void setExtractionRevisionId(Long extractionRevisionId) { this.extractionRevisionId = extractionRevisionId; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSafeMessage() { return safeMessage; }
    public void setSafeMessage(String safeMessage) { this.safeMessage = safeMessage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(Long resolvedBy) { this.resolvedBy = resolvedBy; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
