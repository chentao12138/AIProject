package com.aistudy.server.source.dto;

import com.aistudy.server.source.entity.Source;

import java.time.LocalDateTime;

/**
 * BUSINESS-002 — typed response for all Source endpoints:
 * {@code POST /api/v1/spaces/{spaceId}/sources},
 * {@code GET /api/v1/spaces/{spaceId}/sources},
 * {@code GET /api/v1/spaces/{spaceId}/sources/{sourceId}}.
 *
 * <p>Field names are stable camelCase (the OpenAPI schema source for
 * the future TypeScript client). Deliberately does NOT include
 * {@code createdByUserId} (no guideline requires echoing the
 * internal user id back, consistent with LearningSpaceResponse) and
 * no file-metadata fields (not implemented this round).
 *
 * @param id         DB auto-increment id
 * @param spaceId    parent LearningSpace id
 * @param title      material title
 * @param sourceType documented sourceType value (data-model.md §5.1)
 * @param status     REGISTERED in this slice
 * @param createdAt  registration timestamp
 * @param updatedAt  last-update timestamp (equals createdAt on create)
 */
public record SourceResponse(
        Long id,
        Long spaceId,
        String title,
        String sourceType,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * Maps the persistence entity to the API response shape.
     *
     * @param source entity from the mapper; must not be {@code null}
     * @return the response record
     */
    public static SourceResponse from(Source source) {
        return new SourceResponse(
                source.getId(),
                source.getSpaceId(),
                source.getTitle(),
                source.getSourceType(),
                source.getStatus(),
                source.getCreatedAt(),
                source.getUpdatedAt()
        );
    }
}
