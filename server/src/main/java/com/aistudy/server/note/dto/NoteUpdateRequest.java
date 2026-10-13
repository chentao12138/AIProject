package com.aistudy.server.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request for {@code PUT /api/v1/spaces/{spaceId}/notes/{noteId}}.
 *
 * <p>Full replacement of the note document, like the create body, except that
 * an omitted or {@code null} context ID clears that pointer. {@code createdAt}
 * and the archive timestamp are server-owned and cannot be written.
 */
public record NoteUpdateRequest(
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @NotBlank(message = "content must not be blank")
        @Size(max = 16000, message = "content must be at most 16000 characters")
        String content,

        @Size(max = 32, message = "contentFormat must be at most 32 characters")
        String contentFormat,

        Long sourceId,
        Long sourcePageId,
        Long contentBlockId,
        Long knowledgePointId,
        Long questionId
) {
}
