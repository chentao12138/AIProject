package com.aistudy.server.note.dto;

import com.aistudy.server.note.entity.Note;

import java.time.LocalDateTime;

/**
 * One note plus its primary context pointers.
 *
 * <p>The context IDs are always coherent: when a {@code contentBlockId} is
 * stored, the server also fills in the page and source that own it, so a
 * client can navigate {@code /sources/{sourceId}?page={sourcePageId}} without
 * resolving the block itself. A context that was deleted server-side comes
 * back as {@code null} (the note survives — see V057).
 */
public record NoteView(
        Long id,
        Long spaceId,
        String title,
        String content,
        String contentFormat,
        Long sourceId,
        Long sourcePageId,
        Long contentBlockId,
        Long knowledgePointId,
        Long questionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime archivedAt
) {
    public static NoteView from(Note note) {
        return new NoteView(
                note.getId(),
                note.getSpaceId(),
                note.getTitle(),
                note.getContent(),
                note.getContentFormat(),
                note.getSourceId(),
                note.getSourcePageId(),
                note.getContentBlockId(),
                note.getKnowledgePointId(),
                note.getQuestionId(),
                note.getCreatedAt(),
                note.getUpdatedAt(),
                // Without this the archive is invisible: the list hides archived
                // rows, so a client could neither tell what was archived nor
                // offer a restore.
                note.getArchivedAt());
    }
}
