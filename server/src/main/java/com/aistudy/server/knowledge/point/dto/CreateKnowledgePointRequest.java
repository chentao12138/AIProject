package com.aistudy.server.knowledge.point.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * BUSINESS-003 — request body for
 * {@code POST /api/v1/spaces/{spaceId}/knowledge-points}.
 *
 * <p>Deliberately does NOT contain the server-controlled fields:
 * {@code spaceId} (path), {@code originType} (fixed USER_CURATED),
 * {@code status} (fixed DRAFT), {@code createdByUserId} (JWT sub),
 * {@code publishedAt} (null until publish), {@code deletedAt} (null
 * until soft-delete exists). A client can never choose these.
 *
 * <p>{@code categoryId} is optional; when present it must reference a
 * category of the SAME space (service-enforced, 404 on violation).
 * {@code difficulty} is a free-form bounded string (no enum decided
 * in docs; null = not set).
 */
public record CreateKnowledgePointRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Size(max = 1000, message = "summary must be at most 1000 characters")
        String summary,

        @NotBlank(message = "content must not be blank")
        String content,

        Long categoryId,

        @Size(max = 32, message = "difficulty must be at most 32 characters")
        String difficulty
) {
}
