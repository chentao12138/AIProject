package com.aistudy.server.practice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * BUSINESS-009 — request body for
 * {@code POST /api/v1/spaces/{spaceId}/practice-sessions}.
 *
 * <p>Exactly ONE selection mode must be used (service-enforced 400):
 *
 * <pre>
 *   questionIds               explicit fixed composition (order kept)
 *   knowledgePointId + count  deterministic auto-pick: PUBLISHED
 *                             questions of that point, id ASC, first N
 * </pre>
 *
 * <p>Both modes only ever select PUBLISHED non-deleted questions of
 * the caller's own space; any violation rejects the WHOLE create
 * (404, zero rows).
 */
public record CreatePracticeSessionRequest(
        @Size(min = 1, max = 100, message = "questionIds must contain 1..100 ids")
        List<Long> questionIds,

        Long knowledgePointId,

        @Min(value = 1, message = "count must be >= 1")
        @Max(value = 100, message = "count must be <= 100")
        Integer count
) {
}
