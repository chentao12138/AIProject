package com.aistudy.server.knowledge.point.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("knowledge_point_relation")
public class KnowledgePointRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;
    private Long sourceKnowledgePointId;
    private Long targetKnowledgePointId;
    private String relationType;
    private Integer weight;
    private String notes;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Long getSourceKnowledgePointId() { return sourceKnowledgePointId; }
    public void setSourceKnowledgePointId(Long sourceKnowledgePointId) { this.sourceKnowledgePointId = sourceKnowledgePointId; }
    public Long getTargetKnowledgePointId() { return targetKnowledgePointId; }
    public void setTargetKnowledgePointId(Long targetKnowledgePointId) { this.targetKnowledgePointId = targetKnowledgePointId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
