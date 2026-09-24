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
