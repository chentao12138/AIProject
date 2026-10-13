package com.aistudy.server.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request for {@code POST /api/v1/spaces/{spaceId}/notes}.
 *
 * <p>Owner identity is never part of the body; it comes from the JWT subject.
 * Every context ID must name an entity inside the same LearningSpace, and
 * {@code sourcePageId}/{@code sourceId} are derived from
 * {@code contentBlockId} when the client only has the block.
 *
 * @param title             optional heading; an untitled note renders as its first line
 * @param content           plain text or Markdown body
 * @param contentFormat     {@code PLAIN_TEXT} (default) | {@code MARKDOWN}
 */
public record NoteCreateRequest(
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
