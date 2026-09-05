package com.aistudy.server.knowledge.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-003 — production KnowledgeCategory persistence model.
 *
 * <p>Backs the {@code knowledge_category} table (V006). A category is
 * a node of the user-facing knowledge classification tree INSIDE one
 * LearningSpace (docs/data-model.md §4.2). Root categories have
 * {@code parentId == null}; children reference their parent.
 *
 * <h3>Ownership</h3>
 *
 * <p>No owner column: ownership is derived from the parent
 * LearningSpace through {@code spaceId}. All reads go through
 * owner-scoped SQL that joins {@code learning_space} and constrains
 * {@code ls.owner_subject}. The "parent belongs to the same space"
 * invariant is enforced by the service with scoped SQL — the DB FK
 * alone cannot prove same-space membership.
 *
 * <h3>MyBatis-Plus mapping</h3>
 *
 * <p>{@code @TableName} + {@code @TableId(type = IdType.AUTO)} per
 * project convention; camelCase ↔ snake_case via global
 * {@code map-underscore-to-camel-case}.
 */
@TableName("knowledge_category")
public class KnowledgeCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private Long parentId;

    private String name;

    private String description;

    private Integer sortOrder;

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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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
        return "KnowledgeCategory{id=" + id
                + ", spaceId=" + spaceId
                + ", parentId=" + parentId
                + ", name='" + name + '\''
                + ", sortOrder=" + sortOrder
                + '}';
    }
}
