package com.aistudy.server.search.type;

/**
 * Entity discriminators produced by the C-Search endpoint.
 *
 * <p>The set is also the accepted vocabulary of the {@code types} query
 * parameter, and each value maps to exactly one UNION ALL branch in
 * {@code mapper/SearchMapper.xml}.
 */
public enum SearchEntityType {
    /** Published Source rows only. */
    SOURCE,
    /** Content blocks of a published Source's current extraction revision. */
    CONTENT_BLOCK,
    /** Published KnowledgePoint rows. */
    KNOWLEDGE_POINT,
    /** Published Question rows. */
    QUESTION,
    /** Caller's own wrong answers, whose Question must be published. */
    WRONG_QUESTION,
    /**
     * User-owned learning notes (archived notes excluded by search).
     * A hit of this type is the only one that carries {@code noteId}.
     */
    NOTE
}
