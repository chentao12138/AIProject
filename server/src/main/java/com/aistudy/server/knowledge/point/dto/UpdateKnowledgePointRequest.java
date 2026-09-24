package com.aistudy.server.knowledge.point.dto;

import jakarta.validation.constraints.Size;

/**
 * BUSINESS-003 — update request for KnowledgePoint.
 *
 * @param title       optional new title
 * @param summary     optional new summary
 * @param content     optional new content
 * @param difficulty  optional new difficulty
 * @param categoryId  optional new category (null = uncategorized)
 */
public record UpdateKnowledgePointRequest(
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Size(max = 1000, message = "summary must be at most 1000 characters")
        String summary,

        String content,

        @Size(max = 32, message = "difficulty must be at most 32 characters")
        String difficulty,

        Long categoryId
) {
}
