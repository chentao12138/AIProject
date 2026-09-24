package com.aistudy.server.source.outline.dto;

/**
 * Typed outline update request. Identity fields (nodeId/sourceId/spaceId)
 * come from the path and are never taken from the body.
 */
public record UpdateOutlineNodeRequest(
        Long parentId,
        String title,
        String numberLabel,
        Integer sortOrder,
        Integer startPage,
        Integer endPage,
        String status,
        Double confidence
) {
}
