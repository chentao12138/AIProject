package com.aistudy.server.source.outline.dto;

/**
 * Typed outline create request. {@code sourceId} always comes from the
 * URL path; body may not override resource ownership.
 */
public record CreateOutlineNodeRequest(
        Long parentId,
        String nodeType,
        String title,
        String numberLabel,
        Integer sortOrder,
        Integer startPage,
        Integer endPage,
        String status,
        Double confidence
) {
}
