package com.aistudy.server.question.dto;

import java.util.List;

/**
 * Paged question list (contract request C-Paging / checklist A4.2).
 *
 * <p>Replaces the bare array {@code GET /questions} used to return: the Desktop
 * list may not download every row and page it in the renderer, so paging is
 * decided by the server and {@code totalItems} is what the paginator counts.
 *
 * <p>{@code page} is 0-based, matching the other list endpoints on this
 * service (search, notes). {@code totalPages} is {@code ceil(totalItems /
 * pageSize)} and is {@code 0} only when nothing matches, so an empty result is
 * distinguishable from a missing space (which is a 404, not an empty page).
 */
public record QuestionPageResponse(
        List<QuestionAuthoringResponse> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages
) {
}
