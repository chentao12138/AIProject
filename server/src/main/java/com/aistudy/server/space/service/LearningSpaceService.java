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
 * <h3>Owner isolation rule (restated from the mapper Javadoc)</h3>
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
 *    WHERE owner_subject = ?                (listMine)
 * </pre>
 *
 * <p>An id that exists but belongs to another subject simply does not
 * match the first query — the caller receives {@code null} and the
 * controller maps that to HTTP 404 (api-guidelines.md §13: 404 =
 * "不存在/按安全策略不可见"). No Java-side post-filtering is needed
 * and none exists.
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
 * need no transaction.
 *
 * <h3>Out of scope for this vertical slice</h3>
 *
 * <p>rename / delete / archive / share / member management are NOT
 * implemented — each is a separate future task (TASK-001 scope,
 * docs/current-task.md).
 */
@Service
public class LearningSpaceService {

    /** v1 create always writes ACTIVE; ARCHIVED is reserved for the future archive feature. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    private final LearningSpaceMapper learningSpaceMapper;

    public LearningSpaceService(LearningSpaceMapper learningSpaceMapper) {
        this.learningSpaceMapper = learningSpaceMapper;
    }

    /**
     * Creates a LearningSpace owned by the given subject.
     *
     * <p>The owner subject comes from the caller (the controller
     * resolves it from the authenticated {@code Authentication}).
     * The entity's {@code status} is fixed to ACTIVE and both
     * timestamps are set to the same {@code now} value — the DB
     * defaults exist as a backstop, the application always sets them
     * explicitly so {@code updated_at == created_at} holds by
     * construction on create.
     *
     * @param ownerSubject authenticated JWT subject; never from client input
     * @param request      validated create request (name required)
     * @return the persisted entity with its generated id
     */
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

    /**
     * Lists all LearningSpaces owned by the given subject, newest
     * first. Bounded by {@code owner_subject = ?} in SQL — a caller
     * can never enumerate another subject's spaces through this
     * method.
     *
     * @param ownerSubject authenticated JWT subject
     * @return owned spaces, newest first; empty list when none
     */
    public List<LearningSpace> listMine(String ownerSubject) {
        return learningSpaceMapper.selectByOwner(ownerSubject);
    }

    /**
     * Returns the space with the given id IF AND ONLY IF it belongs
     * to the given subject. {@code null} means either "no such space"
     * or "exists but not yours" — deliberately indistinguishable, so
     * callers cannot probe for the existence of other users' spaces.
     *
     * @param ownerSubject authenticated JWT subject
     * @param spaceId      space id from the request path
     * @return the owned space, or {@code null} if absent / not owned
     */
    public LearningSpace getMine(String ownerSubject, Long spaceId) {
        return learningSpaceMapper.selectByIdAndOwner(spaceId, ownerSubject);
    }
}
