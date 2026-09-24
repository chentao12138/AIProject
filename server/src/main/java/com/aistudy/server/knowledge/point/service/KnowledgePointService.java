package com.aistudy.server.knowledge.point.service;

import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.dto.CreateKnowledgePointRequest;
import com.aistudy.server.knowledge.point.dto.UpdateKnowledgePointRequest;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class KnowledgePointService {

    public static final String ORIGIN_TYPE_USER_CURATED = "USER_CURATED";
    public static final String ORIGIN_TYPE_SOURCE_DERIVED = "SOURCE_DERIVED";
    public static final String ORIGIN_TYPE_AI_DERIVED = "AI_DERIVED";
    public static final String ORIGIN_TYPE_ADMIN_CURATED = "ADMIN_CURATED";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_REJECTED = "REJECTED";

    private final KnowledgePointMapper knowledgePointMapper;
    private final KnowledgeCategoryMapper knowledgeCategoryMapper;
    private final LearningSpaceService learningSpaceService;

    public KnowledgePointService(KnowledgePointMapper knowledgePointMapper,
                                 KnowledgeCategoryMapper knowledgeCategoryMapper,
                                 LearningSpaceService learningSpaceService) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.knowledgeCategoryMapper = knowledgeCategoryMapper;
        this.learningSpaceService = learningSpaceService;
    }

    @Transactional
    public KnowledgePoint create(String ownerSubject, Long spaceId, CreateKnowledgePointRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        if (request.categoryId() != null
                && knowledgeCategoryMapper.selectByIdAndSpaceAndOwner(
                        request.categoryId(), spaceId, ownerSubject) == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        KnowledgePoint point = new KnowledgePoint();
        point.setSpaceId(spaceId);
        point.setCategoryId(request.categoryId());
        point.setTitle(request.title());
        point.setSummary(request.summary());
        point.setContent(request.content());
        point.setOriginType(ORIGIN_TYPE_USER_CURATED);
        point.setStatus(STATUS_DRAFT);
        point.setDifficulty(request.difficulty());
        point.setCreatedByUserId(ownerSubject);
        point.setCreatedAt(now);
        point.setUpdatedAt(now);
        knowledgePointMapper.insert(point);
        return point;
    }

    public List<KnowledgePoint> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return knowledgePointMapper.selectBySpaceOwner(spaceId, ownerSubject);
    }

    public KnowledgePoint getMine(String ownerSubject, Long spaceId, Long knowledgePointId) {
        return knowledgePointMapper.selectByIdSpaceOwner(knowledgePointId, spaceId, ownerSubject);
    }

    @Transactional
    public KnowledgePoint update(String ownerSubject, Long spaceId, Long knowledgePointId, UpdateKnowledgePointRequest request) {
        KnowledgePoint existing = getMine(ownerSubject, spaceId, knowledgePointId);
        if (existing == null || STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        if (request.categoryId() != null
                && knowledgeCategoryMapper.selectByIdAndSpaceAndOwner(
                        request.categoryId(), spaceId, ownerSubject) == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = knowledgePointMapper.updateDetailsByIdAndSpace(
                knowledgePointId, spaceId,
                request.title() != null ? request.title() : existing.getTitle(),
                request.summary() != null ? request.summary() : existing.getSummary(),
                request.content() != null ? request.content() : existing.getContent(),
                request.difficulty() != null ? request.difficulty() : existing.getDifficulty(),
                request.categoryId() != null ? request.categoryId() : existing.getCategoryId(),
                now);
        if (updated == 0) {
            return null;
        }
        existing.setTitle(request.title() != null ? request.title() : existing.getTitle());
        existing.setSummary(request.summary() != null ? request.summary() : existing.getSummary());
        existing.setContent(request.content() != null ? request.content() : existing.getContent());
        existing.setDifficulty(request.difficulty() != null ? request.difficulty() : existing.getDifficulty());
        existing.setCategoryId(request.categoryId() != null ? request.categoryId() : existing.getCategoryId());
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public KnowledgePoint archive(String ownerSubject, Long spaceId, Long knowledgePointId) {
        KnowledgePoint existing = getMine(ownerSubject, spaceId, knowledgePointId);
        if (existing == null || STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = knowledgePointMapper.archiveByIdAndSpace(
                knowledgePointId, spaceId, STATUS_ARCHIVED, now, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_ARCHIVED);
        existing.setArchivedAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public KnowledgePoint restore(String ownerSubject, Long spaceId, Long knowledgePointId) {
        KnowledgePoint existing = getMine(ownerSubject, spaceId, knowledgePointId);
        if (existing == null || !STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = knowledgePointMapper.restoreByIdAndSpace(knowledgePointId, spaceId, STATUS_DRAFT, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_DRAFT);
        existing.setArchivedAt(null);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public KnowledgePoint reject(String ownerSubject, Long spaceId, Long knowledgePointId, String reason) {
        KnowledgePoint existing = getMine(ownerSubject, spaceId, knowledgePointId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = knowledgePointMapper.rejectByIdAndSpace(
                knowledgePointId, spaceId, STATUS_REJECTED, now, reason, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_REJECTED);
        existing.setRejectedAt(now);
        existing.setRejectedReason(reason);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public KnowledgePoint publish(String ownerSubject, Long spaceId, Long knowledgePointId) {
        KnowledgePoint existing = getMine(ownerSubject, spaceId, knowledgePointId);
        if (existing == null) {
            return null;
        }
        if (STATUS_PUBLISHED.equals(existing.getStatus())) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = knowledgePointMapper.publishByIdAndSpace(
                knowledgePointId, spaceId, STATUS_PUBLISHED, now, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_PUBLISHED);
        existing.setPublishedAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }
}
