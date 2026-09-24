package com.aistudy.server.search.type;

public enum SearchEntityType {
    SOURCE,
    CONTENT_BLOCK,
    KNOWLEDGE_POINT,
    QUESTION,
    WRONG_QUESTION,
    /** User-owned learning notes (archived notes excluded by search). */
    NOTE
}
