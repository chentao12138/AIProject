package com.aistudy.server.knowledge.source.dto;

import com.aistudy.server.knowledge.source.entity.KnowledgePointSource;

import java.time.LocalDateTime;

/**
 * BUSINESS-007 — typed KnowledgePointSource response (provenance
 * link). The client can fetch the full block content through the
 * content-blocks API; this record carries the citation identity plus
 * the two ids needed to navigate to the cited location (source and
 * source page of that block).
 *
 * @param id                link id
 * @param spaceId           parent space id (same for point and block)
 * @param knowledgePointId  cited knowledge point
 * @param contentBlockId    cited content block
 * @param sourceId          source of the cited block (null when the
 *                          block row is gone)
 * @param sourcePageId      page of the cited block (null when the
 *                          block row is gone)
 * @param relationType      optional citation type (null in V1)
 * @param relevanceScore    optional relevance (null in V1)
 * @param createdAt         link creation timestamp
 */
public record KnowledgePointSourceResponse(
        Long id,
        Long spaceId,
        Long knowledgePointId,
        Long contentBlockId,
        Long sourceId,
        Long sourcePageId,
        String relationType,
        Double relevanceScore,
        LocalDateTime createdAt) {

    public static KnowledgePointSourceResponse from(KnowledgePointSource link) {
        return new KnowledgePointSourceResponse(
                link.getId(),
                link.getSpaceId(),
                link.getKnowledgePointId(),
                link.getContentBlockId(),
                link.getSourceId(),
                link.getSourcePageId(),
                link.getRelationType(),
                link.getRelevanceScore(),
                link.getCreatedAt());
    }
}
