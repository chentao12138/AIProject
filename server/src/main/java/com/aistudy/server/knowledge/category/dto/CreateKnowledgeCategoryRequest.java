package com.aistudy.server.knowledge.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * BUSINESS-003 — request body for
 * {@code POST /api/v1/spaces/{spaceId}/knowledge-categories}.
 *
 * <p>Deliberately does NOT contain {@code spaceId} (it is in the
 * path), {@code ownerSubject} (resolved server-side from the JWT),
 * or {@code createdAt}/{@code updatedAt} (server-maintained).
 *
 * <p>{@code parentId} null = root category; non-null must reference a
 * category of the SAME space (service-enforced, 404 on violation).
 * {@code sortOrder} is optional and defaults to 0 (smallest =
 * presented first by the list query ORDER BY sort_order ASC).
 */
public record CreateKnowledgeCategoryRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 128, message = "name must be at most 128 characters")
        String name,

        @Size(max = 512, message = "description must be at most 512 characters")
        String description,

        Long parentId,

        Integer sortOrder
) {
}
