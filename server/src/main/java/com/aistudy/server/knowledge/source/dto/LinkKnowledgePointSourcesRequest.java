package com.aistudy.server.knowledge.source.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * BUSINESS-007 — request body for
 * {@code POST /api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/sources}.
 *
 * <p>Batch link: one call attaches several ContentBlocks to a point
 * (a point derives from many blocks, ADR-040). All block ids must
 * belong to the SAME space as the point and be owned by the caller —
 * any invalid id rejects the whole request with 404 (no partial
 * insert). Already-linked pairs are no-ops (idempotent add).
 *
 * @param contentBlockIds non-empty list of content block ids to link
 */
public record LinkKnowledgePointSourcesRequest(
        @NotEmpty(message = "contentBlockIds must not be empty")
        List<@NotNull(message = "contentBlockIds entries must not be null") Long> contentBlockIds) {
}
