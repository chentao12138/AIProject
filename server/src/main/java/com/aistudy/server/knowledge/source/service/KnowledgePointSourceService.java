package com.aistudy.server.knowledge.source.service;

import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.service.KnowledgePointService;
import com.aistudy.server.knowledge.source.entity.KnowledgePointSource;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.service.ContentBlockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BUSINESS-007 — application service for KnowledgePointSource
 * provenance links (data-model.md §8.2, R-KNOW-002, ADR-039).
 *
 * <p>Infrastructure only — no AI extraction populates links yet
 * (runbook §7.3). Linking does NOT change the point's originType:
 * originType semantics belong to the creation flow (SOURCE_DERIVED /
 * AI_DERIVED points will be created WITH their links by the future
 * extraction slice; USER_CURATED points may cite source blocks too).
 *
 * <h3>Same-space invariant (runbook §7.2, CRITICAL)</h3>
 *
 * <p>Before ANY insert, BOTH endpoints are validated with owner-
 * scoped reads against the path spaceId:
 *
 * <ol>
 *   <li>knowledge point: {@link KnowledgePointService#getMine}
 *       (id + spaceId + owner + deleted_at IS NULL) — null → 404</li>
 *   <li>every content block: {@link ContentBlockService#getMine}
 *       (id + spaceId + owner, source↔space consistency in SQL) —
 *       any null → 404, NO partial insert</li>
 * </ol>
 *
 * <p>Because both reads are constrained to the path spaceId and the
 * owner, a cross-space point or block can never be linked. Reads are
 * owner-scoped JOINs too (point + block + learning_space in one
 * query) — nothing leaks across spaces.
 *
 * <h3>Idempotent add</h3>
 *
 * <p>Pairs already linked are skipped (uk_knowledge_point_source_pair
 * backs this); the response is the point's FULL current link list.
 */
@Service
public class KnowledgePointSourceService {

    private final KnowledgePointSourceMapper knowledgePointSourceMapper;
    private final KnowledgePointService knowledgePointService;
    private final ContentBlockService contentBlockService;

    public KnowledgePointSourceService(KnowledgePointSourceMapper knowledgePointSourceMapper,
                                       KnowledgePointService knowledgePointService,
                                       ContentBlockService contentBlockService) {
        this.knowledgePointSourceMapper = knowledgePointSourceMapper;
        this.knowledgePointService = knowledgePointService;
        this.contentBlockService = contentBlockService;
    }

    /**
     * Links one or more ContentBlocks to the caller's own knowledge
     * point. Already-linked pairs are no-ops.
     *
     * @return the point's FULL current link list after the operation
     *         (ordered by id), or {@code null} when the point or ANY
     *         block is absent / not owned / cross-space (404)
     */
    @Transactional
    public List<KnowledgePointSource> link(String ownerSubject,
                                           Long spaceId,
                                           Long knowledgePointId,
                                           List<Long> contentBlockIds) {
        // 1. Endpoint validation (same-space invariant): point first.
        KnowledgePoint point = knowledgePointService.getMine(
                ownerSubject, spaceId, knowledgePointId);
        if (point == null) {
            return null;
        }

        // 2. Every block must be owned and in the SAME space.
        Set<Long> uniqueIds = new LinkedHashSet<>(contentBlockIds);
        for (Long blockId : uniqueIds) {
            ContentBlock block = contentBlockService.getMine(ownerSubject, spaceId, blockId);
            if (block == null) {
                return null; // absent / not owned / cross-space — no partial insert
            }
        }

        // 3. Dedup: skip pairs already linked.
        List<Long> existing = knowledgePointSourceMapper.selectExistingBlockIds(
                spaceId, knowledgePointId, List.copyOf(uniqueIds));
        Set<Long> existingSet = new LinkedHashSet<>(existing);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (Long blockId : uniqueIds) {
            if (existingSet.contains(blockId)) {
                continue;
            }
            KnowledgePointSource link = new KnowledgePointSource();
            link.setSpaceId(spaceId);
            link.setKnowledgePointId(knowledgePointId);
            link.setContentBlockId(blockId);
            link.setRelationType(null);
            link.setRelevanceScore(null);
            link.setCreatedByUserId(ownerSubject);
            link.setCreatedAt(now);
            knowledgePointSourceMapper.insert(link);
        }

        // 4. Return the point's full current link list.
        return knowledgePointSourceMapper.selectBySpacePointOwner(
                spaceId, knowledgePointId, ownerSubject);
    }

    /**
     * Lists the provenance links of the caller's own point.
     *
     * @return links (possibly empty), or {@code null} when the point
     *         is absent / not owned / deleted (404)
     */
    public List<KnowledgePointSource> listMine(String ownerSubject,
                                               Long spaceId,
                                               Long knowledgePointId) {
        if (knowledgePointService.getMine(ownerSubject, spaceId, knowledgePointId) == null) {
            return null;
        }
        return knowledgePointSourceMapper.selectBySpacePointOwner(
                spaceId, knowledgePointId, ownerSubject);
    }
}
