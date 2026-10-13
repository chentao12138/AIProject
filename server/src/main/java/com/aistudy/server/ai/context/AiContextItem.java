package com.aistudy.server.ai.context;

import com.aistudy.server.search.dto.SearchResult;
import com.aistudy.server.search.type.SearchEntityType;
import java.util.ArrayList;
import java.util.List;

/**
 * AI-003 — bounded learning context item with provenance.
 *
 * <p>{@code locator} is the human-readable label ("P12"); the three ID fields
 * are the machine-readable route the client deep-links on. Only a selection the
 * learner made by ID (AI-004 C03) sets {@code learnerSelected}, which is what
 * makes it outrank automatically retrieved material.
 */
public record AiContextItem(
        String type,
        Long entityId,
        String title,
        String snippet,
        String locator,
        Long sourceId,
        Long sourcePageId,
        Long contentBlockId,
        boolean learnerSelected
) {

    public AiContextItem(String type, Long entityId, String title, String snippet) {
        this(type, entityId, title, snippet, null, null, null, null, false);
    }

    public static AiContextItem from(SearchResult result) {
        if (result == null) {
            return null;
        }
        SearchEntityType entityType = result.type();
        String title = result.title() == null ? "" : result.title();
        String snippet = result.snippet() == null ? "" : result.snippet();
        return new AiContextItem(
                entityType == null ? "UNKNOWN" : entityType.name(),
                result.id(),
                title,
                snippet,
                result.pageNumber() == null ? null : "P" + result.pageNumber(),
                result.sourceId(),
                result.sourcePageId(),
                entityType == SearchEntityType.CONTENT_BLOCK ? result.id() : null,
                false
        );
    }

    /** Identity used to drop an automatic hit that the learner already pinned by ID. */
    public String dedupeKey() {
        return type + "|" + entityId;
    }

    public static List<AiContextItem> fromAll(List<SearchResult> results) {
        List<AiContextItem> items = new ArrayList<>();
        if (results == null) {
            return items;
        }
        for (SearchResult result : results) {
            AiContextItem item = from(result);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }
}
