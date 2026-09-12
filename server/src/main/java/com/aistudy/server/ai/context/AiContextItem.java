package com.aistudy.server.ai.context;

import com.aistudy.server.search.dto.SearchResult;
import java.util.ArrayList;
import java.util.List;

/**
 * AI-003 — bounded learning context item with provenance.
 */
public record AiContextItem(
        String type,
        Long entityId,
        String title,
        String snippet
) {

    public static AiContextItem from(SearchResult result) {
        if (result == null) {
            return null;
        }
        String title = result.title() == null ? "" : result.title();
        String snippet = result.snippet() == null ? "" : result.snippet();
        return new AiContextItem(
                result.type() == null ? "UNKNOWN" : result.type().name(),
                result.id(),
                title,
                snippet
        );
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
