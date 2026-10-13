package com.aistudy.server.note.mapper;

/**
 * Filter + window for one page of the owner's notes.
 *
 * <p>Every field except {@code spaceId}, {@code ownerSubject}, {@code limit}
 * and {@code offset} is optional: a null filter is skipped by the statement, so
 * the same query serves the plain list, the archive tab, and the
 * entity → notes direction of checklist A8.3.
 *
 * @param keywordLike      ready-made LIKE pattern (wildcards included, user
 *                         input already escaped) or null
 * @param knowledgePointId matches the note's primary pointer OR an M:N link row
 * @param sourceId         matches the primary pointer OR any block of that source
 * @param archived         null and false both mean "active notes only";
 *                         true returns the archive tab
 */
public record NoteListQuery(Long spaceId,
                            String ownerSubject,
                            String keywordLike,
                            Long knowledgePointId,
                            Long sourceId,
                            Long contentBlockId,
                            Long questionId,
                            Boolean archived,
                            int limit,
                            int offset) {
}
