package com.aistudy.server.note.dto;

import java.util.List;

/**
 * Paged note list. {@code page} is 1-based; the shape follows the shared
 * pagination envelope in api-guidelines.md §11.
 */
public record NotePageResponse(
        List<NoteView> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages
) {
}
