package com.aistudy.server.source.folder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("folder_import_snapshot")
public class FolderImportSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;
    private Long sourceId;
    private Integer totalFiles;
    private Integer unchangedFiles;
    private Integer addedFiles;
    private Integer removedFiles;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Integer getTotalFiles() { return totalFiles; }
    public void setTotalFiles(Integer totalFiles) { this.totalFiles = totalFiles; }
    public Integer getUnchangedFiles() { return unchangedFiles; }
    public void setUnchangedFiles(Integer unchangedFiles) { this.unchangedFiles = unchangedFiles; }
    public Integer getAddedFiles() { return addedFiles; }
    public void setAddedFiles(Integer addedFiles) { this.addedFiles = addedFiles; }
    public Integer getRemovedFiles() { return removedFiles; }
    public void setRemovedFiles(Integer removedFiles) { this.removedFiles = removedFiles; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
