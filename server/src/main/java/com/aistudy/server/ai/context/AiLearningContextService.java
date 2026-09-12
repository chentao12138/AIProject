package com.aistudy.server.ai.context;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.search.dto.SearchPageResponse;
import com.aistudy.server.search.service.SearchService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI-003 — assembles bounded learning context via {@link SearchService}.
 *
 * <p>Never bypasses SearchService. Search already enforces LearningSpace
 * isolation, WrongQuestion user isolation, and Question correctness hiding.
 */
@Service
public class AiLearningContextService {

    private static final Logger log = LoggerFactory.getLogger(AiLearningContextService.class);

    private final SearchService searchService;
    private final AiProperties aiProperties;

    public AiLearningContextService(SearchService searchService, AiProperties aiProperties) {
        this.searchService = searchService;
        this.aiProperties = aiProperties;
    }

    public List<AiContextItem> assemble(String ownerSubject,
                                        String userSubject,
                                        Long spaceId,
                                        String userMessage) {
        String query = deriveQuery(userMessage);
        if (query.isBlank()) {
            return List.of();
        }
        int maxResults = Math.max(1, aiProperties.getContext().getMaxSearchResults());
        try {
            SearchPageResponse page = searchService.search(
                    ownerSubject, userSubject, spaceId, query, null, 0, maxResults);
            if (page == null || page.content() == null) {
                return List.of();
            }
            return AiContextItem.fromAll(page.content());
        } catch (RuntimeException e) {
            // Context assembly must not break the tutor turn; log and continue empty.
            log.warn("AI learning context retrieval failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    public String renderContextBlock(List<AiContextItem> items) {
        if (items == null || items.isEmpty()) {
            return "(no matching learning material found)";
        }
        int maxChars = Math.max(500, aiProperties.getContext().getMaxContextChars());
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (AiContextItem item : items) {
            String line = index + ". [" + item.type() + "] "
                    + safe(item.title())
                    + " — "
                    + safe(item.snippet())
                    + System.lineSeparator();
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append(line);
            index++;
        }
        if (builder.isEmpty()) {
            builder.append("(learning material present but too large to include)");
        }
        return builder.toString();
    }

    private static String deriveQuery(String userMessage) {
        if (userMessage == null) {
            return "";
        }
        String trimmed = userMessage.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        // Prefer the first non-trivial line as a retrieval key.
        String[] lines = trimmed.split("\\R");
        for (String line : lines) {
            String candidate = line.trim();
            if (candidate.length() >= 2) {
                return candidate.length() > 80 ? candidate.substring(0, 80) : candidate;
            }
        }
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 400 ? collapsed.substring(0, 400) + "..." : collapsed;
    }
}
