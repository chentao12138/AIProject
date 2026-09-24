package com.aistudy.server.space.service;

import com.aistudy.server.space.dto.CreateLearningSpaceRequest;
import com.aistudy.server.space.entity.LearningSpace;
import com.aistudy.server.space.mapper.LearningSpaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-001 — application service for LearningSpace.
 *
 * <p>This is the ONLY caller of
 * {@link LearningSpaceMapper} (architecture.md: "Mapper 不被其他模块
 * 任意直接调用以绕过应用服务"). Controllers call this service; the
 * mapper is never touched from the controller layer.
 *
 * <h3>Owner isolation rule</h3>
 *
 * <p>The inherited unscoped {@code BaseMapper} methods
 * ({@code selectById(id)}, {@code selectList(...)},
 * {@code selectOne(...)}) MUST never be invoked from this class or
 * anywhere else in the module. Every persistence read goes through
 * the two owner-scoped queries:
 *
 * <pre>
 *   SELECT * FROM learning_space
 *    WHERE id = ? AND owner_subject = ?     (getMine)
 *
 *   SELECT * FROM learning_space
 *    WHERE owner_subject = ?                (listMine / listMineIncludingArchived)
 * </pre>
 *
 * <p>An id that exists but belongs to another subject simply does not
 * match the first query — the caller receives {@code null} and the
 * controller maps that to HTTP 404 (api-guidelines.md §13: 404 =
 * "不存在/按安全策略不可见"). No Java-side post-filtering is needed
 * and none exists.
 *
 * <h3>Lifecycle</h3>
 *
 * <p>Spaces can be renamed, archived, and restored. Archived spaces
 * are excluded from normal list operations unless explicitly
 * requested. All writes to an archived space are rejected.
 *
 * <h3>Owner source</h3>
 *
 * <p>{@code ownerSubject} is always the authenticated JWT
 * {@code sub}, passed in by the controller from the Spring Security
 * {@code Authentication}. It is never accepted from request bodies,
 * query parameters, or headers (api-guidelines.md §2).
 *
 * <h3>Transactions</h3>
 *
 * <p>{@link #create} is {@code @Transactional}: insert + any future
 * follow-up work commit atomically. Reads are single-statement and
 * need no transaction. Lifecycle mutations (rename/archive/restore)
 * are each single-row UPDATEs under their own transaction.
 */
@Service
public class LearningSpaceService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    private final LearningSpaceMapper learningSpaceMapper;

    public LearningSpaceService(LearningSpaceMapper learningSpaceMapper) {
        this.learningSpaceMapper = learningSpaceMapper;
    }

    @Transactional
    public LearningSpace create(String ownerSubject, CreateLearningSpaceRequest request) {
        LocalDateTime now = LocalDateTime.now();

        LearningSpace space = new LearningSpace();
        space.setName(request.name());
        space.setDescription(request.description());
        space.setOwnerSubject(ownerSubject);
        space.setStatus(STATUS_ACTIVE);
        space.setCreatedAt(now);
        space.setUpdatedAt(now);

        learningSpaceMapper.insert(space);
        return space;
    }

    public List<LearningSpace> listMine(String ownerSubject) {
        return learningSpaceMapper.selectByOwner(ownerSubject);
    }

    public List<LearningSpace> listMineIncludingArchived(String ownerSubject) {
        return learningSpaceMapper.selectByOwnerIncludingArchived(ownerSubject);
    }

    public LearningSpace getMine(String ownerSubject, Long spaceId) {
        return learningSpaceMapper.selectByIdAndOwner(spaceId, ownerSubject);
    }

    @Transactional
    public LearningSpace rename(String ownerSubject, Long spaceId, String newName, String newDescription) {
        LearningSpace space = getMine(ownerSubject, spaceId);
        if (space == null || STATUS_ARCHIVED.equals(space.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = learningSpaceMapper.updateNameDescriptionByIdAndOwner(
                spaceId, ownerSubject, newName, newDescription, now);
        if (updated == 0) {
            return null;
        }
        space.setName(newName);
        space.setDescription(newDescription);
        space.setUpdatedAt(now);
        return space;
    }

    @Transactional
    public LearningSpace archive(String ownerSubject, Long spaceId) {
        LearningSpace space = getMine(ownerSubject, spaceId);
        if (space == null || STATUS_ARCHIVED.equals(space.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = learningSpaceMapper.archiveByIdAndOwner(spaceId, ownerSubject, STATUS_ARCHIVED, now, now);
        if (updated == 0) {
            return null;
        }
        space.setStatus(STATUS_ARCHIVED);
        space.setArchivedAt(now);
        space.setUpdatedAt(now);
        return space;
    }

    @Transactional
    public LearningSpace restore(String ownerSubject, Long spaceId) {
        LearningSpace space = getMine(ownerSubject, spaceId);
        if (space == null || !STATUS_ARCHIVED.equals(space.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = learningSpaceMapper.restoreByIdAndOwner(spaceId, ownerSubject, STATUS_ACTIVE, now);
        if (updated == 0) {
            return null;
        }
        space.setStatus(STATUS_ACTIVE);
        space.setArchivedAt(null);
        space.setUpdatedAt(now);
        return space;
    }
}
