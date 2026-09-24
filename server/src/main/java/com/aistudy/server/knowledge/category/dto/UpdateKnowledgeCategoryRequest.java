package com.aistudy.server.knowledge.category.dto;

import jakarta.validation.constraints.Size;

/**
 * BUSINESS-003 — update request for KnowledgeCategory.
 *
 * @param name        optional new name
 * @param description optional new description
 * @param sortOrder   optional new sort order
 */
public record UpdateKnowledgeCategoryRequest(
        @Size(max = 128, message = "name must be at most 128 characters")
        String name,

        @Size(max = 512, message = "description must be at most 512 characters")
        String description,

        Integer sortOrder
) {
}
