package com.aistudy.server.search.dto;

import com.aistudy.server.search.model.SearchProjection;
import com.aistudy.server.search.type.SearchEntityType;
import com.aistudy.server.search.util.SnippetGenerator;

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
        /** Set only when {@code type} is NOTE; the id the client routes on. */
        Long noteId
) {

    /** Overload for results that carry no note pointer. */
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
        this(type, id, title, snippet, score, sourceId, sourceAssetId, sourcePageId,
                pageNumber, knowledgePointId, questionId, null);
    }

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
