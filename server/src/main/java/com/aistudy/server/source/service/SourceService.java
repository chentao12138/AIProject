package com.aistudy.server.source.service;

import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.source.dto.CreateSourceRequest;
import com.aistudy.server.source.dto.UpdateSourceRequest;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.mapper.SourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-002 — application service for Source.
 *
 * <p>This is the ONLY caller of {@link SourceMapper}
 * (architecture.md: "Mapper 不被其他模块任意直接调用以绕过应用服务").
 *
 * <h3>Authorization model</h3>
 *
 * <p>A Source's ownership is derived from its parent LearningSpace
 * (V005 intentionally has no owner column). Every operation first
 * proves the parent space belongs to the caller via
 * {@link LearningSpaceService#getMine(String, Long)} — the SAME
 * owner-scoped query (id + ownerSubject) that BUSINESS-001 already
 * uses — and only then touches source data.
 *
 * <h3>Source lifecycle</h3>
 *
 * <p>Sources support the full lifecycle:
 * REGISTERED → PROCESSING → NEEDS_REVIEW → PUBLISHED → ARCHIVED
 * with REJECTED as a terminal alternative. ADMIN_MANUAL sources
 * bypass the ingestion pipeline and go straight to NEEDS_REVIEW or
 * PUBLISHED.
 */
@Service
public class SourceService {

    public static final String SOURCE_TYPE_DESKTOP_UPLOAD = "DESKTOP_UPLOAD";
    public static final String SOURCE_TYPE_DESKTOP_FOLDER_IMPORT = "DESKTOP_FOLDER_IMPORT";
    public static final String SOURCE_TYPE_ADMIN_UPLOAD = "ADMIN_UPLOAD";
    public static final String SOURCE_TYPE_ADMIN_MANUAL = "ADMIN_MANUAL";

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_REJECTED = "REJECTED";

    private final SourceMapper sourceMapper;
    private final LearningSpaceService learningSpaceService;

    public SourceService(SourceMapper sourceMapper,
                         LearningSpaceService learningSpaceService) {
        this.sourceMapper = sourceMapper;
        this.learningSpaceService = learningSpaceService;
    }

    @Transactional
    public Source create(String ownerSubject, Long spaceId, CreateSourceRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        Source source = new Source();
        source.setSpaceId(spaceId);
        source.setTitle(request.title());
        source.setSourceType(request.sourceType() != null ? request.sourceType() : SOURCE_TYPE_DESKTOP_UPLOAD);
        source.setStatus(STATUS_REGISTERED);
        source.setCreatedByUserId(ownerSubject);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);

        sourceMapper.insert(source);
        return source;
    }

    @Transactional
    public Source createManual(String adminSubject, Long spaceId, String title, String content) {
        if (learningSpaceService.getMine(adminSubject, spaceId) == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        Source source = new Source();
        source.setSpaceId(spaceId);
        source.setTitle(title);
        source.setSourceType(SOURCE_TYPE_ADMIN_MANUAL);
        source.setStatus(STATUS_NEEDS_REVIEW);
        source.setReviewStatus(STATUS_NEEDS_REVIEW);
        source.setCreatedByUserId(adminSubject);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);

        sourceMapper.insert(source);
        return source;
    }

    public List<Source> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return sourceMapper.selectBySpaceId(spaceId);
    }

    public Source getMine(String ownerSubject, Long spaceId, Long sourceId) {
        return sourceMapper.selectByIdAndSpaceAndOwner(sourceId, spaceId, ownerSubject);
    }

    @Transactional
    public Source update(String ownerSubject, Long spaceId, Long sourceId, UpdateSourceRequest request) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null || STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.updateTitleByIdAndSpace(
                sourceId, spaceId,
                request.title() != null ? request.title() : existing.getTitle(),
                now);
        if (updated == 0) {
            return null;
        }
        existing.setTitle(request.title() != null ? request.title() : existing.getTitle());
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Source archive(String ownerSubject, Long spaceId, Long sourceId) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null || STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.archiveByIdAndSpace(sourceId, spaceId, STATUS_ARCHIVED, now, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_ARCHIVED);
        existing.setArchivedAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Source restore(String ownerSubject, Long spaceId, Long sourceId) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null || !STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.restoreByIdAndSpace(sourceId, spaceId, STATUS_REGISTERED, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_REGISTERED);
        existing.setArchivedAt(null);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Source transitionStatus(String ownerSubject, Long spaceId, Long sourceId, String newStatus) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.updateStatusByIdAndSpace(sourceId, spaceId, newStatus, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(newStatus);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Source review(String ownerSubject, Long spaceId, Long sourceId, String reviewStatus, String reviewedBy, String rejectedReason) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.updateReviewByIdAndSpace(
                sourceId, spaceId, reviewStatus, now, reviewedBy, rejectedReason, now);
        if (updated == 0) {
            return null;
        }
        existing.setReviewStatus(reviewStatus);
        existing.setReviewedAt(now);
        existing.setReviewedBy(reviewedBy);
        existing.setRejectedReason(rejectedReason);
        existing.setUpdatedAt(now);
        if (STATUS_PUBLISHED.equals(reviewStatus)) {
            existing.setStatus(STATUS_PUBLISHED);
        } else if (STATUS_REJECTED.equals(reviewStatus)) {
            existing.setStatus(STATUS_REJECTED);
        }
        return existing;
    }
}
