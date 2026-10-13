package com.aistudy.server.source.outline.service;

import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.outline.dto.CreateOutlineNodeRequest;
import com.aistudy.server.source.outline.dto.UpdateOutlineNodeRequest;
import com.aistudy.server.source.outline.entity.SourceOutlineNode;
import com.aistudy.server.source.outline.mapper.SourceOutlineNodeMapper;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Outline tree service.
 *
 * <p>Path {@code sourceId} is the ownership truth. Create never accepts
 * a client-supplied sourceId. Parent nodes must belong to the same
 * source; cycles are rejected. Delete with children is refused (409).
 */
@Service
public class SourceOutlineNodeService {

    /**
     * Bound on one proposed tree. A mis-parsed document can yield thousands of
     * heading-shaped lines; writing them all would flood the review queue and the
     * response, and a reviewer cannot act on that many suggestions anyway.
     */
    public static final int MAX_PROPOSED_NODES = 300;


    private final SourceOutlineNodeMapper sourceOutlineNodeMapper;
    private final LearningSpaceService learningSpaceService;
    private final SourceService sourceService;

    public SourceOutlineNodeService(SourceOutlineNodeMapper sourceOutlineNodeMapper,
                                    LearningSpaceService learningSpaceService,
                                    SourceService sourceService) {
        this.sourceOutlineNodeMapper = sourceOutlineNodeMapper;
        this.learningSpaceService = learningSpaceService;
        this.sourceService = sourceService;
    }

    public List<SourceOutlineNode> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        return sourceOutlineNodeMapper.selectBySpaceAndSource(spaceId, sourceId);
    }

    @Transactional
    /** How many nodes of one revision were extracted with low confidence (F-06). */
    public int countLowConfidence(Long spaceId, Long revisionId, double threshold) {
        return sourceOutlineNodeMapper.countLowConfidenceByRevision(spaceId, revisionId, threshold);
    }

    /** Nodes of one revision. Read through the owning module for version compare. */
    public List<SourceOutlineNode> listByRevision(Long spaceId, Long revisionId) {
        return sourceOutlineNodeMapper.selectByRevision(revisionId, spaceId);
    }

    /**
     * One heading of a proposed tree: its text and its nesting level
     * (1 = chapter, 2 = section, 3 = subsection).
     */
    public record ProposedHeading(String title, int level) {
    }

    /**
     * Replaces the proposed outline of one revision (audit F-07).
     *
     * <p>The "自动目录建议 → 人工审核" flow had no producer at all: AI structuring
     * only classified block types, so the outline tree existed solely from manual
     * CRUD. Headings come from the document itself — never from knowledge
     * categories, which are a different axis (the requirement calls this out).
     *
     * <p>Every node is written as {@code NEEDS_REVIEW} and stamped with the
     * revision it was derived from, so the reviewer sees exactly what the machine
     * proposed, and a re-run replaces only proposals: a node that was confirmed
     * or edited by a human survives.
     *
     * @return nodes written, 0 when the document offered no usable headings
     */
    @Transactional
    public int replaceProposedTree(String ownerSubject, Long spaceId, Long sourceId,
                                   Long revisionId, List<ProposedHeading> headings) {
        if (revisionId == null || headings == null || headings.isEmpty()) {
            return 0;
        }
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null
                || sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return 0;
        }
        sourceOutlineNodeMapper.deleteProposedByRevision(revisionId, spaceId);

        LocalDateTime now = LocalDateTime.now();
        java.util.Deque<SourceOutlineNode> ancestors = new java.util.ArrayDeque<>();
        int written = 0;
        for (ProposedHeading heading : headings) {
            if (heading == null || heading.title() == null || heading.title().isBlank()) {
                continue;
            }
            if (written >= MAX_PROPOSED_NODES) {
                break;
            }
            int level = Math.min(3, Math.max(1, heading.level()));
            while (ancestors.size() >= level) {
                ancestors.removeLast();
            }
            SourceOutlineNode parent = ancestors.peekLast();
            SourceOutlineNode node = new SourceOutlineNode();
            node.setSpaceId(spaceId);
            node.setSourceId(sourceId);
            node.setExtractionRevisionId(revisionId);
            node.setParentId(parent == null ? null : parent.getId());
            node.setNodeType(level == 1 ? "CHAPTER" : level == 2 ? "SECTION" : "SUBSECTION");
            node.setTitle(truncateTitle(heading.title()));
            node.setNumberLabel(leadingNumberLabel(heading.title()));
            node.setSortOrder(written);
            node.setStatus("NEEDS_REVIEW");
            node.setCreatedAt(now);
            node.setUpdatedAt(now);
            sourceOutlineNodeMapper.insert(node);
            ancestors.addLast(node);
            written++;
        }
        return written;
    }

    private static String truncateTitle(String title) {
        String trimmed = title.trim();
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }

    /** "第3章 关系数据库" → "第3章", "12.1 Access" → "12.1"; otherwise no label. */
    private static String leadingNumberLabel(String title) {
        String trimmed = title.trim();
        java.util.regex.Matcher chinese =
                java.util.regex.Pattern.compile("^第[0-9一二三四五六七八九十百]+[章节]").matcher(trimmed);
        if (chinese.find()) {
            return chinese.group();
        }
        java.util.regex.Matcher numeric =
                java.util.regex.Pattern.compile("^[0-9]+(\\.[0-9]+)*").matcher(trimmed);
        return numeric.find() ? numeric.group() : null;
    }

    public SourceOutlineNode create(String ownerSubject, Long spaceId, Long sourceId,
                                    CreateOutlineNodeRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            return null;
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        Long parentId = request.parentId();
        if (parentId != null) {
            SourceOutlineNode parent = sourceOutlineNodeMapper.selectByIdAndSpace(parentId, spaceId);
            if (parent == null || !sourceId.equals(parent.getSourceId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "parent must belong to the same source");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        SourceOutlineNode node = new SourceOutlineNode();
        node.setSpaceId(spaceId);
        // Path is the only allowed sourceId; body cannot override ownership.
        node.setSourceId(sourceId);
        node.setParentId(parentId);
        node.setNodeType(request.nodeType() != null ? request.nodeType() : "SECTION");
        node.setTitle(request.title());
        node.setNumberLabel(request.numberLabel());
        node.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        node.setStartPage(request.startPage());
        node.setEndPage(request.endPage());
        node.setStatus(request.status() != null ? request.status() : "NEEDS_REVIEW");
        node.setConfidence(request.confidence());
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        sourceOutlineNodeMapper.insert(node);
        return node;
    }

    @Transactional
    public SourceOutlineNode update(String ownerSubject, Long spaceId, Long sourceId,
                                    Long nodeId, UpdateOutlineNodeRequest patch) {
        SourceOutlineNode existing = requireOwnedNode(ownerSubject, spaceId, sourceId, nodeId);
        if (patch.parentId() != null && !patch.parentId().equals(existing.getParentId())) {
            if (patch.parentId().equals(nodeId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parent cycle");
            }
            SourceOutlineNode parent = sourceOutlineNodeMapper.selectByIdAndSpace(patch.parentId(), spaceId);
            if (parent == null || !sourceId.equals(parent.getSourceId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "parent must belong to the same source");
            }
            // Cycle check: walk ancestors of the new parent.
            Set<Long> seen = new HashSet<>();
            Long cursor = patch.parentId();
            while (cursor != null) {
                if (!seen.add(cursor)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parent cycle");
                }
                if (cursor.equals(nodeId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parent cycle");
                }
                SourceOutlineNode n = sourceOutlineNodeMapper.selectByIdAndSpace(cursor, spaceId);
                cursor = n == null ? null : n.getParentId();
            }
            existing.setParentId(patch.parentId());
        }
        if (patch.title() != null) existing.setTitle(patch.title());
        if (patch.numberLabel() != null) existing.setNumberLabel(patch.numberLabel());
        if (patch.sortOrder() != null) existing.setSortOrder(patch.sortOrder());
        if (patch.startPage() != null) existing.setStartPage(patch.startPage());
        if (patch.endPage() != null) existing.setEndPage(patch.endPage());
        if (patch.status() != null) existing.setStatus(patch.status());
        if (patch.confidence() != null) existing.setConfidence(patch.confidence());
        existing.setUpdatedAt(LocalDateTime.now());
        sourceOutlineNodeMapper.updateById(existing);
        return existing;
    }

    /**
     * Delete a node. Refuses when children exist so tree shape stays
     * explicit (caller must reparent/delete children first).
     */
    @Transactional
    public void delete(String ownerSubject, Long spaceId, Long sourceId, Long nodeId) {
        SourceOutlineNode existing = requireOwnedNode(ownerSubject, spaceId, sourceId, nodeId);
        int children = sourceOutlineNodeMapper.countChildren(nodeId);
        if (children > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "node has children; reparent or delete children first");
        }
        sourceOutlineNodeMapper.deleteById(existing.getId());
    }

    private SourceOutlineNode requireOwnedNode(String ownerSubject, Long spaceId, Long sourceId, Long nodeId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SourceOutlineNode not found");
        }
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SourceOutlineNode not found");
        }
        SourceOutlineNode existing = sourceOutlineNodeMapper.selectByIdAndSpace(nodeId, spaceId);
        if (existing == null || !sourceId.equals(existing.getSourceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SourceOutlineNode not found");
        }
        return existing;
    }
}
