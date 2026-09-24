package com.aistudy.server.knowledge.point.service;

import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.entity.KnowledgePointRelation;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointRelationMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class KnowledgePointRelationService {

    private final KnowledgePointRelationMapper knowledgePointRelationMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final LearningSpaceService learningSpaceService;

    public KnowledgePointRelationService(KnowledgePointRelationMapper knowledgePointRelationMapper,
                                         KnowledgePointMapper knowledgePointMapper,
                                         LearningSpaceService learningSpaceService) {
        this.knowledgePointRelationMapper = knowledgePointRelationMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.learningSpaceService = learningSpaceService;
    }

    public List<KnowledgePointRelation> listRelations(String ownerSubject, Long spaceId, Long kpId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return new ArrayList<>();
        }
        List<KnowledgePointRelation> outgoing = knowledgePointRelationMapper
                .selectBySourceAndSpace(kpId, spaceId, ownerSubject);
        List<KnowledgePointRelation> incoming = knowledgePointRelationMapper
                .selectByTargetAndSpace(kpId, spaceId, ownerSubject);
        List<KnowledgePointRelation> merged = new ArrayList<>(outgoing);
        merged.addAll(incoming);
        return merged;
    }

    @Transactional
    public KnowledgePointRelation addRelation(String ownerSubject,
                                              Long spaceId,
                                              Long sourceKpId,
                                              Long targetKpId,
                                              String relationType,
                                              Integer weight,
                                              String notes) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        KnowledgePoint source = knowledgePointMapper.selectByIdSpaceOwner(sourceKpId, spaceId, ownerSubject);
        KnowledgePoint target = knowledgePointMapper.selectByIdSpaceOwner(targetKpId, spaceId, ownerSubject);
        if (source == null || target == null) {
            return null;
        }
        if (checkCycle(spaceId, sourceKpId, targetKpId, relationType, ownerSubject)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "relation would create a cycle");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        KnowledgePointRelation relation = new KnowledgePointRelation();
        relation.setSpaceId(spaceId);
        relation.setSourceKnowledgePointId(sourceKpId);
        relation.setTargetKnowledgePointId(targetKpId);
        relation.setRelationType(relationType);
        relation.setWeight(weight);
        relation.setNotes(notes);
        relation.setCreatedAt(now);
        knowledgePointRelationMapper.insert(relation);
        return relation;
    }

    @Transactional
    public void removeRelation(String ownerSubject, Long spaceId, Long relationId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        KnowledgePointRelation existing = knowledgePointRelationMapper.selectByIdAndSpace(
                relationId, spaceId, ownerSubject);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "relation not found");
        }
        knowledgePointRelationMapper.deleteById(relationId);
    }

    private boolean checkCycle(Long spaceId, Long sourceKpId, Long targetKpId, String relationType, String ownerSubject) {
        if (!"PREREQUISITE".equals(relationType) && !"PART_OF".equals(relationType)) {
            return false;
        }
        Set<Long> visited = new HashSet<>();
        List<Long> stack = new ArrayList<>();
        stack.add(targetKpId);
        while (!stack.isEmpty()) {
            Long current = stack.remove(stack.size() - 1);
            if (sourceKpId.equals(current)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            List<KnowledgePointRelation> outgoing = knowledgePointRelationMapper
                    .selectBySourceAndSpace(current, spaceId, ownerSubject);
            for (KnowledgePointRelation rel : outgoing) {
                if (relationType.equals(rel.getRelationType())) {
                    stack.add(rel.getTargetKnowledgePointId());
                }
            }
        }
        return false;
    }
}
