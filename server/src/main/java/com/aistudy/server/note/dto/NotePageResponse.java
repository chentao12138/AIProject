package com.aistudy.server.note.dto;

import java.util.List;

/**
 * Paged note list. {@code page} is 0-based — the first page is {@code page=0},
 * which is what NoteController defaults to and what the offset arithmetic uses;
 * the shape follows the shared pagination envelope in api-guidelines.md §11.
 */
public record NotePageResponse(
        List<NoteView> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages
) {
}
