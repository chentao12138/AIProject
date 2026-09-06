package com.aistudy.server.source.content.dto;

import com.aistudy.server.source.content.entity.ContentBlock;

import java.time.LocalDateTime;

/**
 * BUSINESS-006 — typed ContentBlock response.
 *
 * <p>{@code locatorJson} / {@code structuredDataJson} are opaque
 * server-side JSON texts (V1: locator = line range
 * {@code {"lineStart":N,"lineEnd":M}} for future provenance; structured
 * data stays null until table/formula parsing lands).
 *
 * @param id                  block id
 * @param spaceId             parent space id
 * @param sourceId            parent source id
 * @param sourcePageId        anchoring page id
 * @param blockType           HEADING | PARAGRAPH | LIST | TABLE | FIGURE |
 *                            CODE | FORMULA | OTHER
 * @param sortOrder           document order (0-based)
 * @param normalizedText      block text (line endings normalized)
 * @param structuredDataJson  opaque structured data (null in V1)
 * @param locatorJson         opaque locator (V1: line range)
 * @param createdAt           creation timestamp
 * @param updatedAt           last-update timestamp
 */
public record ContentBlockResponse(
        Long id,
        Long spaceId,
        Long sourceId,
        Long sourcePageId,
        String blockType,
        Integer sortOrder,
        String normalizedText,
        String structuredDataJson,
        String locatorJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ContentBlockResponse from(ContentBlock block) {
        return new ContentBlockResponse(
                block.getId(),
                block.getSpaceId(),
                block.getSourceId(),
                block.getSourcePageId(),
                block.getBlockType(),
                block.getSortOrder(),
                block.getNormalizedText(),
                block.getStructuredDataJson(),
                block.getLocatorJson(),
                block.getCreatedAt(),
                block.getUpdatedAt());
    }
}
