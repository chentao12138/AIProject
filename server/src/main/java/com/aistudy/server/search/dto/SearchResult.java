package com.aistudy.server.search.dto;

import com.aistudy.server.search.model.SearchProjection;
import com.aistudy.server.search.type.SearchEntityType;
import com.aistudy.server.search.util.SnippetGenerator;

/**
 * One typed search hit returned by
 * {@code GET /api/v1/spaces/{spaceId}/search} (C-Search contract).
 *
 * <p>Exactly one of the entity identifier columns is non-null and it agrees
 * with {@code type}; {@code sourceId} / {@code sourceAssetId} /
 * {@code sourcePageId} / {@code pageNumber} carry provenance for
 * {@code CONTENT_BLOCK} hits only. Every row is already constrained by the
 * visibility rule documented in {@code SearchMapper.xml}: unpublished or
 * superseded content never reaches this DTO.
 *
 * @param type            entity discriminator, never {@code null}
 * @param id              id of the entity itself (note id for NOTE rows)
 * @param title           display title, may be truncated for CONTENT_BLOCK
 * @param snippet         250-character context window around the query term
 * @param score           deterministic tier score plus FULLTEXT relevance
 *                        boost; always greater than zero
 * @param sourceId        owning Source id, {@code null} unless the hit is a
 *                        SOURCE or CONTENT_BLOCK
 * @param sourceAssetId   provenance asset id, CONTENT_BLOCK only
 * @param sourcePageId    provenance page id, CONTENT_BLOCK only
 * @param pageNumber      provenance page number, CONTENT_BLOCK only
 * @param knowledgePointId set only for KNOWLEDGE_POINT hits
 * @param questionId      set for QUESTION and WRONG_QUESTION hits
 * @param noteId          set only for NOTE hits; lets the Desktop client open
 *                        the note directly without parsing {@code sortKey}
 */
public record SearchResult(
        SearchEntityType type,
        Long id,
        String title,
        String snippet,
        Integer score,
        Long sourceId,
        Long sourceAssetId,
        Long sourcePageId,
        Integer pageNumber,
        Long knowledgePointId,
        Long questionId,
        Long noteId
) {

    /**
     * Compatibility constructor for callers that build hits without note
     * provenance (all non-NOTE entities); equivalent to {@code noteId == null}.
     *
     * @param type             entity discriminator
     * @param id               entity id
     * @param title            display title
     * @param snippet          snippet text
     * @param score            ranking score
     * @param sourceId         owning source id or {@code null}
     * @param sourceAssetId    owning asset id or {@code null}
     * @param sourcePageId     owning page id or {@code null}
     * @param pageNumber       page number or {@code null}
     * @param knowledgePointId knowledge point id or {@code null}
     * @param questionId       question id or {@code null}
     */
    public SearchResult(SearchEntityType type,
                        Long id,
                        String title,
                        String snippet,
                        Integer score,
                        Long sourceId,
                        Long sourceAssetId,
                        Long sourcePageId,
                        Integer pageNumber,
                        Long knowledgePointId,
                        Long questionId) {
        this(type, id, title, snippet, score, sourceId, sourceAssetId,
                sourcePageId, pageNumber, knowledgePointId, questionId, null);
    }

    /**
     * Maps a mapper projection onto the client contract.
     *
     * @param projection raw UNION row; must have a non-null {@code entityType}
     *                   that exists in {@link SearchEntityType}
     * @param query      normalized query used to build the snippet
     * @return the result row; never {@code null}
     * @throws IllegalArgumentException if {@code projection.entityType} is not a
     *                                  known {@link SearchEntityType} value
     */
    public static SearchResult from(SearchProjection projection, String query) {
        String snippet = SnippetGenerator.snippet(
                projection.getSearchText(), query, 250);
        return new SearchResult(
                SearchEntityType.valueOf(projection.getEntityType()),
                projection.getEntityId(),
                projection.getTitle(),
                snippet,
                projection.getScore(),
                projection.getSourceId(),
                projection.getSourceAssetId(),
                projection.getSourcePageId(),
                projection.getPageNumber(),
                projection.getKnowledgePointId(),
                projection.getQuestionId(),
                projection.getNoteId()
        );
    }
}
