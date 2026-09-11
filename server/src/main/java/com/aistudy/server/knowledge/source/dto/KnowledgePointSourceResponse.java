package com.aistudy.server.knowledge.source.dto;

import com.aistudy.server.knowledge.source.entity.KnowledgePointSource;

import java.time.LocalDateTime;

/**
 * BUSINESS-007 — typed KnowledgePointSource response (provenance
 * link). The client can fetch the full block content through the
 * content-blocks API; this record carries the citation identity only.
 *
 * @param id                link id
 * @param spaceId           parent space id (same for point and block)
 * @param knowledgePointId  cited knowledge point
 * @param contentBlockId    cited content block
 * @param relationType      optional citation type (null in V1)
 * @param relevanceScore    optional relevance (null in V1)
 * @param createdAt         link creation timestamp
 */
public record KnowledgePointSourceResponse(
        Long id,
        Long spaceId,
        Long knowledgePointId,
        Long contentBlockId,
        String relationType,
        Double relevanceScore,
        LocalDateTime createdAt) {

    public static KnowledgePointSourceResponse from(KnowledgePointSource link) {
        return new KnowledgePointSourceResponse(
                link.getId(),
                link.getSpaceId(),
                link.getKnowledgePointId(),
                link.getContentBlockId(),
                link.getRelationType(),
                link.getRelevanceScore(),
                link.getCreatedAt());
    }
}
