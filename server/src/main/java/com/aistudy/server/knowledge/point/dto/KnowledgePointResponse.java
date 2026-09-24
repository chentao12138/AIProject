package com.aistudy.server.knowledge.point.dto;

import com.aistudy.server.knowledge.point.entity.KnowledgePoint;

import java.time.LocalDateTime;

/**
 * BUSINESS-003 — typed response for KnowledgePoint endpoints.
 *
 * <p>Stable camelCase fields (OpenAPI schema source). Deliberately
 * does NOT return {@code createdByUserId} (internal audit identity;
 * consistent with LearningSpace/Source responses) and does NOT return
 * {@code deletedAt} (soft-delete marker is server-internal).
 *
 * @param id          point id
 * @param spaceId     parent LearningSpace id
 * @param categoryId  category id or {@code null}
 * @param title       point title
 * @param summary     optional summary
 * @param content     point content
 * @param originType  always USER_CURATED in this slice
 * @param status      DRAFT or PUBLISHED
 * @param difficulty  free-form difficulty or {@code null}
 * @param createdAt   creation timestamp
 * @param updatedAt   last-update timestamp
 * @param publishedAt publish timestamp, {@code null} while DRAFT
 */
public record KnowledgePointResponse(
        Long id,
        Long spaceId,
        Long categoryId,
        String title,
        String summary,
        String content,
        String originType,
        String status,
        String difficulty,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime publishedAt,
        LocalDateTime archivedAt
) {

    public static KnowledgePointResponse from(KnowledgePoint point) {
        return new KnowledgePointResponse(
                point.getId(),
                point.getSpaceId(),
                point.getCategoryId(),
                point.getTitle(),
                point.getSummary(),
                point.getContent(),
                point.getOriginType(),
                point.getStatus(),
                point.getDifficulty(),
                point.getCreatedAt(),
                point.getUpdatedAt(),
                point.getPublishedAt(),
                point.getArchivedAt()
        );
    }
}
