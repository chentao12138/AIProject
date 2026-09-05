package com.aistudy.server.knowledge.point.service;

import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.dto.CreateKnowledgePointRequest;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * BUSINESS-003 — application service for KnowledgePoint.
 *
 * <p>The ONLY caller of {@link KnowledgePointMapper}. All reads are
 * owner-scoped SQL (JOIN learning_space + deleted_at IS NULL).
 *
 * <h3>Server-controlled fields</h3>
 *
 * <p>On create the service fixes:
 * {@code originType = USER_CURATED}, {@code status = DRAFT},
 * {@code createdByUserId = authentication.getName()},
 * {@code publishedAt = null}, {@code deletedAt = null}. The client
 * request DTO carries none of these.
 *
 * <h3>Category same-space invariant</h3>
 *
 * <p>When {@code categoryId != null}, the category must exist,
 * belong to the SAME {@code spaceId}, and that space must belong to
 * the current owner. The check uses the owner-scoped
 * {@link KnowledgeCategoryMapper#selectByIdAndSpaceAndOwner} — a
 * categoryId from another space (or another owner) returns
 * {@code null} → 404. This prevents
 * {@code knowledge_point.space_id != knowledge_category.space_id}
 * (docs/data-model.md §20 forbids cross-space relations).
 *
 * <h3>Publish lifecycle</h3>
 *
 * <p>{@code DRAFT → PUBLISHED}: status=PUBLISHED, publishedAt=now,
 * updatedAt=now. The target is owner-scoped (proven by
 * {@link #getMine} inside the same transaction, then a scoped
 * UPDATE with {@code WHERE id = ? AND space_id = ? AND
 * deleted_at IS NULL} — never an unscoped {@code updateById}).
 *
 * <p>Publishing an already-PUBLISHED point is a TRUE idempotent
 * no-op: the resource is returned unchanged (no UPDATE, no timestamp
 * refresh), so double-click / retry cannot mutate the row. Only the
 * DRAFT → PUBLISHED transition writes. (api-guidelines.md has no
 * conflicting state-machine rule for this flow.)
 *
 * <h3>Null/empty contract (controller maps to HTTP)</h3>
 *
 * <ul>
 *   <li>{@link #create} returns {@code null} when the space is not
 *       owned or the categoryId is invalid → 404.</li>
 *   <li>{@link #listMine} returns {@code null} when the space is not
 *       owned → 404.</li>
 *   <li>{@link #getMine} returns {@code null} → 404.</li>
 *   <li>{@link #publish} returns {@code null} when the point is not
 *       found/not owned → 404; otherwise the refreshed entity.</li>
 * </ul>
 */
@Service
public class KnowledgePointService {

    public static final String ORIGIN_TYPE_USER_CURATED = "USER_CURATED";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

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

    /**
     * Creates a USER_CURATED DRAFT KnowledgePoint inside the
     * caller's own space, optionally under a category of the SAME
     * space.
     *
     * @return persisted point, or {@code null} when the space is not
     *         owned or the categoryId is invalid (404)
     */
    @Transactional
    public KnowledgePoint create(String ownerSubject,
                                 Long spaceId,
                                 CreateKnowledgePointRequest request) {
        // 1. Space ownership (id + ownerSubject, SQL-scoped).
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        // 2. Category same-space invariant (if a category is given).
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
        // publishedAt / deletedAt stay null (DRAFT, not deleted).

        knowledgePointMapper.insert(point);
        return point;
    }

    /**
     * Lists non-deleted points of the caller's own space, newest
     * first.
     *
     * @return points, or {@code null} when the space is not owned
     */
    public List<KnowledgePoint> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return knowledgePointMapper.selectBySpaceOwner(spaceId, ownerSubject);
    }

    /**
     * Returns ONE non-deleted point of the caller's own space
     * (owner-scoped SQL; {@code null} → 404).
     */
    public KnowledgePoint getMine(String ownerSubject, Long spaceId, Long knowledgePointId) {
        return knowledgePointMapper.selectByIdSpaceOwner(knowledgePointId, spaceId, ownerSubject);
    }

    /**
     * Publishes a DRAFT point (DRAFT → PUBLISHED).
     *
     * <p>TRUE idempotency: publishing an already-PUBLISHED point is a
     * NO-OP — the existing resource is returned unchanged (same
     * {@code status}, same {@code publishedAt}, same {@code updatedAt})
     * and no UPDATE is executed. Double-click / retry therefore cannot
     * mutate the row or change any timestamp; the response is 200 with
     * the current resource. This matches the established semantic
     * "如果已经 PUBLISHED：保持幂等，返回当前资源 200" — re-publish must
     * NOT refresh timestamps.
     *
     * <p>Only the DRAFT → PUBLISHED transition executes the UPDATE
     * (status=PUBLISHED, publishedAt=now, updatedAt=now). The
     * timestamp is normalized to MySQL DATETIME(6) precision with
     * {@code truncatedTo(ChronoUnit.MICROS)} so the UPDATE parameters,
     * the response value, and the DB round-trip are exactly equal —
     * the idempotent re-publish path (returning the DB-loaded entity)
     * then satisfies exact-equality assertions.
     *
     * <p>Authorization: the point must belong to {@code spaceId} and
     * that space to the current owner. The owner-scoped read happens
     * FIRST (same transaction); the UPDATE is additionally scoped by
     * {@code id + space_id + deleted_at IS NULL}.
     *
     * @return the refreshed point, or {@code null} when not found /
     *         not owned (404)
     */
    @Transactional
    public KnowledgePoint publish(String ownerSubject, Long spaceId, Long knowledgePointId) {
        // Owner-scoped existence check (404 semantics). Also filters
        // deleted_at IS NULL — a soft-deleted point cannot be
        // published.
        KnowledgePoint existing = getMine(ownerSubject, spaceId, knowledgePointId);
        if (existing == null) {
            return null;
        }

        // Already PUBLISHED → idempotent no-op: return the current
        // resource unchanged, no UPDATE, no timestamp refresh.
        if (STATUS_PUBLISHED.equals(existing.getStatus())) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = knowledgePointMapper.publishByIdAndSpace(
                knowledgePointId,
                spaceId,
                STATUS_PUBLISHED,
                now,
                now);

        // updated == 0 is not expected here (we just read the row in
        // the same transaction), but treat it as 404 for safety.
        if (updated == 0) {
            return null;
        }

        // Return the refreshed entity so the response carries the new
        // status/publishedAt without a second round-trip.
        existing.setStatus(STATUS_PUBLISHED);
        existing.setPublishedAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }
}
