package com.aistudy.server.ai.context;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.search.dto.SearchPageResponse;
import com.aistudy.server.search.service.SearchService;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AI-003 — assembles bounded learning context.
 *
 * <p>Automatic retrieval never bypasses {@link SearchService}: search already
 * enforces LearningSpace isolation, WrongQuestion user isolation, and Question
 * correctness hiding. C03 selections are the one exception — the learner named
 * the entity by ID, so the server looks it up by primary key and validates it
 * against the caller's LearningSpace before reading any body text. That
 * authorization check mirrors {@code NoteService.applyContext} and returns 400
 * {@code VALIDATION_ERROR} for anything outside the space.
 */
@Service
public class AiLearningContextService {

    /**
     * Reference type for a selected source page. {@code SearchEntityType} has no
     * page member (pages are not full-text searchable), so this value exists only
     * for C03 selections; it is documented in V058's {@code reference_type} check.
     */
    public static final String REFERENCE_TYPE_SOURCE_PAGE = "SOURCE_PAGE";

    private static final Logger log = LoggerFactory.getLogger(AiLearningContextService.class);
    /** Matches the {@code snippet} column budget in {@code AiMessagePersistenceService}. */
    private static final int SNIPPET_CHARS = 2000;
    /** How much of a selected Source's own text to pull in when no page/block is named. */
    private static final int SOURCE_PREVIEW_BLOCKS = 5;

    private final SearchService searchService;
    private final AiProperties aiProperties;
    private final ContentBlockMapper contentBlockMapper;
    private final SourcePageMapper sourcePageMapper;
    private final SourceMapper sourceMapper;
    private final KnowledgePointMapper knowledgePointMapper;

    public AiLearningContextService(SearchService searchService,
                                    AiProperties aiProperties,
                                    ContentBlockMapper contentBlockMapper,
                                    SourcePageMapper sourcePageMapper,
                                    SourceMapper sourceMapper,
                                    KnowledgePointMapper knowledgePointMapper) {
        this.searchService = searchService;
        this.aiProperties = aiProperties;
        this.contentBlockMapper = contentBlockMapper;
        this.sourcePageMapper = sourcePageMapper;
        this.sourceMapper = sourceMapper;
        this.knowledgePointMapper = knowledgePointMapper;
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

    /**
     * C03 — automatic context behind the learner's own selections. Selected items
     * keep their order and are never dropped by the bound; automatic hits only
     * fill what is left and never duplicate a pinned entity.
     */
    public List<AiContextItem> assemble(String ownerSubject,
                                        String userSubject,
                                        Long spaceId,
                                        String userMessage,
                                        List<AiContextItem> selectedItems) {
        List<AiContextItem> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (selectedItems != null) {
            for (AiContextItem item : selectedItems) {
                if (item != null && seen.add(item.dedupeKey())) {
                    merged.add(item);
                }
            }
        }
        int bound = Math.max(1, aiProperties.getContext().getMaxSearchResults());
        int cap = Math.max(bound, merged.size());
        if (merged.size() < cap) {
            for (AiContextItem item : assemble(ownerSubject, userSubject, spaceId, userMessage)) {
                if (merged.size() >= cap) {
                    break;
                }
                if (item == null || !seen.add(item.dedupeKey())) {
                    continue;
                }
                merged.add(item);
            }
        }
        return merged;
    }

    /**
     * C03 — resolve the IDs the client sent into context items carrying body text
     * and structured provenance. Order is narrowest-first (block, page, source,
     * knowledge point) and a block or page fixes its owning IDs, so the caller can
     * hand the result straight to {@link #assemble(String, String, Long, String, List)}.
     *
     * @throws ApiException 400 {@code VALIDATION_ERROR} when an ID does not exist,
     *                      lives in another LearningSpace, or is soft-deleted
     */
    public List<AiContextItem> resolveSelectedContext(String ownerSubject,
                                                      Long spaceId,
                                                      Long sourceId,
                                                      Long sourcePageId,
                                                      Long contentBlockId,
                                                      Long knowledgePointId) {
        List<AiContextItem> items = new ArrayList<>();
        Long resolvedSourceId = sourceId;
        Long resolvedPageId = sourcePageId;

        if (contentBlockId != null) {
            ContentBlock block = contentBlockMapper.selectById(contentBlockId);
            if (block == null || !spaceId.equals(block.getSpaceId())) {
                throw unknownContext("contentBlockId");
            }
            resolvedPageId = block.getSourcePageId();
            resolvedSourceId = block.getSourceId();
            items.add(blockItem(block, resolvedPageId));
        } else if (resolvedPageId != null) {
            SourcePage page = sourcePageMapper.selectById(resolvedPageId);
            if (page == null || !spaceId.equals(page.getSpaceId())) {
                throw unknownContext("sourcePageId");
            }
            if (resolvedSourceId != null && !resolvedSourceId.equals(page.getSourceId())) {
                throw mismatchedContext("sourceId", "sourcePageId");
            }
            resolvedSourceId = page.getSourceId();
            items.add(pageItem(page));
        }

        if (resolvedSourceId != null) {
            Source source = sourceMapper.selectById(resolvedSourceId);
            if (source == null || !spaceId.equals(source.getSpaceId())) {
                throw unknownContext("sourceId");
            }
            if (items.isEmpty()) {
                // A Source-level question has no body text of its own, so the
                // server pulls a bounded preview of its blocks alongside the title.
                items.add(sourceItem(source));
                items.addAll(sourceBlockPreview(ownerSubject, spaceId, source));
            }
        }

        if (knowledgePointId != null) {
            KnowledgePoint point = knowledgePointMapper.selectById(knowledgePointId);
            if (point == null || !spaceId.equals(point.getSpaceId()) || point.getDeletedAt() != null) {
                throw unknownContext("knowledgePointId");
            }
            items.add(knowledgePointItem(point));
        }
        return items;
    }

    public String renderContextBlock(List<AiContextItem> items) {
        if (items == null || items.isEmpty()) {
            return "(no matching learning material found)";
        }
        int maxChars = Math.max(500, aiProperties.getContext().getMaxContextChars());
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (AiContextItem item : items) {
            String line = index + ". [" + item.type()
                    + (item.learnerSelected() ? " · learner-selected" : "") + "] "
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

    private AiContextItem blockItem(ContentBlock block, Long pageId) {
        String text = block.getNormalizedText();
        return new AiContextItem(
                "CONTENT_BLOCK",
                block.getId(),
                previewTitle(text),
                bounded(text),
                pageLocator(pageId),
                block.getSourceId(),
                block.getSourcePageId(),
                block.getId(),
                true);
    }

    private AiContextItem pageItem(SourcePage page) {
        return new AiContextItem(
                REFERENCE_TYPE_SOURCE_PAGE,
                page.getId(),
                "Page " + (page.getSourcePageNumber() == null ? "?" : page.getSourcePageNumber()),
                bounded(page.getExtractedText()),
                page.getSourcePageNumber() == null ? null : "P" + page.getSourcePageNumber(),
                page.getSourceId(),
                page.getId(),
                null,
                true);
    }

    private AiContextItem sourceItem(Source source) {
        return new AiContextItem(
                "SOURCE",
                source.getId(),
                source.getTitle(),
                null,
                null,
                source.getId(),
                null,
                null,
                true);
    }

    private AiContextItem knowledgePointItem(KnowledgePoint point) {
        String body = (point.getSummary() == null ? "" : point.getSummary() + " ")
                + (point.getContent() == null ? "" : point.getContent());
        return new AiContextItem(
                "KNOWLEDGE_POINT",
                point.getId(),
                point.getTitle(),
                bounded(body.isBlank() ? null : body.trim()),
                null,
                null,
                null,
                null,
                true);
    }

    /** Bounded block preview of a selected Source, with page numbers resolved in one read. */
    private List<AiContextItem> sourceBlockPreview(String ownerSubject, Long spaceId, Source source) {
        List<ContentBlock> blocks;
        try {
            blocks = contentBlockMapper.selectBySpaceSourceOwner(spaceId, source.getId(), ownerSubject);
        } catch (RuntimeException e) {
            log.warn("AI selected-source preview failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> pageNumbers = new LinkedHashMap<>();
        List<AiContextItem> items = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block == null || block.getNormalizedText() == null
                    || block.getNormalizedText().isBlank()) {
                continue;
            }
            if (items.size() >= SOURCE_PREVIEW_BLOCKS) {
                break;
            }
            Integer pageNumber = pageNumbers.computeIfAbsent(
                    block.getSourcePageId() == null ? -1L : block.getSourcePageId(),
                    key -> lookupPageNumber(key));
            items.add(new AiContextItem(
                    "CONTENT_BLOCK",
                    block.getId(),
                    previewTitle(block.getNormalizedText()),
                    bounded(block.getNormalizedText()),
                    pageNumber == null ? null : "P" + pageNumber,
                    source.getId(),
                    block.getSourcePageId(),
                    block.getId(),
                    true));
        }
        return items;
    }

    private Integer lookupPageNumber(Long sourcePageId) {
        if (sourcePageId == null || sourcePageId < 0) {
            return null;
        }
        SourcePage page = sourcePageMapper.selectById(sourcePageId);
        return page == null ? null : page.getSourcePageNumber();
    }

    private String pageLocator(Long sourcePageId) {
        Integer pageNumber = lookupPageNumber(sourcePageId);
        return pageNumber == null ? null : "P" + pageNumber;
    }

    private static String previewTitle(String text) {
        if (text == null || text.isBlank()) {
            return "Content block";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 120 ? collapsed.substring(0, 120) : collapsed;
    }

    private static String bounded(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= SNIPPET_CHARS ? value : value.substring(0, SNIPPET_CHARS);
    }

    private static ApiException unknownContext(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                field + " does not reference an entity in this learning space");
    }

    private static ApiException mismatchedContext(String field, String otherField) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                field + " does not contain the given " + otherField);
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
