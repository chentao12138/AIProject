package com.aistudy.server.knowledge.category.dto;

import com.aistudy.server.knowledge.category.entity.KnowledgeCategory;

import java.time.LocalDateTime;

/**
 * BUSINESS-003 — typed response for KnowledgeCategory endpoints.
 *
 * <p>Stable camelCase fields (OpenAPI schema source for the shared
 * TypeScript client). No owner/space-owner fields echoed back.
 *
 * @param id          category id
 * @param spaceId     parent LearningSpace id
 * @param parentId    parent category id, {@code null} for root
 * @param name        category name
 * @param description optional description
 * @param sortOrder   presentation order (ascending)
 * @param createdAt   creation timestamp
 * @param updatedAt   last-update timestamp
 */
public record KnowledgeCategoryResponse(
        Long id,
        Long spaceId,
        Long parentId,
        String name,
        String description,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static KnowledgeCategoryResponse from(KnowledgeCategory category) {
        return new KnowledgeCategoryResponse(
                category.getId(),
                category.getSpaceId(),
                category.getParentId(),
                category.getName(),
                category.getDescription(),
                category.getSortOrder(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
}
