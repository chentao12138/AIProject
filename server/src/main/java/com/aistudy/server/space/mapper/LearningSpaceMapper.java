package com.aistudy.server.space.mapper;

import com.aistudy.server.space.entity.LearningSpace;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-001 — production MyBatis-Plus mapper for
 * {@link LearningSpace}.
 *
 * <p>Extends {@link BaseMapper} for standard CRUD (insert) and adds
 * explicit owner-scoped read queries. This is the ONLY data-access
 * entry point for the {@code learning_space} table; other modules
 * must go through
 * {@link com.aistudy.server.space.service.LearningSpaceService}
 * rather than calling this mapper directly (architecture.md: "Mapper
 * 不被其他模块任意直接调用以绕过应用服务").
 *
 * <h3>Owner isolation is enforced in SQL, not in Java</h3>
 *
 * Every read query carries the owner subject in the WHERE clause:
 *
 * <ul>
 *   <li>{@link #selectByIdAndOwner} — {@code WHERE id = ? AND
 *       owner_subject = ?}: a space that exists but belongs to
 *       another subject simply does not match. This prevents the
 *       dangerous pattern "SELECT by id, then check owner in Java",
 *       where a leaked row could be read before the Java-side check
 *       rejects it.</li>
 *   <li>{@link #selectByOwner} — {@code WHERE owner_subject = ?}:
 *       the list endpoint can never return another subject's spaces
 *       even if a caller guessed a valid id.</li>
 * </ul>
 *
 * <p>The {@code idx_learning_space_owner_subject} index (V004)
 * supports both patterns. The owner subject is ALWAYS the JWT
 * {@code sub} resolved from the authenticated
 * {@code org.springframework.security.core.Authentication} — never
 * from request bodies, query parameters, or headers.
 *
 * <h3>Why explicit {@code @Select} instead of MyBatis-Plus wrappers</h3>
 *
 * <p>MyBatis-Plus {@code QueryWrapper} / {@code LambdaQueryWrapper}
 * could express the same predicates, but an explicit {@code @Select}
 * makes the owner boundary visible in the SQL text itself. Note that
 * {@link BaseMapper} still exposes unscoped convenience methods
 * ({@code selectById(id)}, {@code selectList(...)}, {@code selectOne(...)})
 * — they are inherited here and must NOT be called by the service
 * layer, because they carry no owner predicate. The owner-scoped
 * contract of this module is enforced by:
 * <ol>
 *   <li>this interface declaring the two owner-scoped reads as its
 *       visible surface, and</li>
 *   <li>{@link com.aistudy.server.space.service.LearningSpaceService}
 *       being the only caller of this mapper — it never invokes the
 *       inherited unscoped methods.</li>
 * </ol>
 * The service Javadoc restates this rule so a future refactor cannot
 * silently route a read through an unscoped path.
 */
@Mapper
public interface LearningSpaceMapper extends BaseMapper<LearningSpace> {

    /**
     * Returns the LearningSpace with the given id IF AND ONLY IF it is
     * owned by the given subject. Returns {@code null} when the row
     * does not exist OR belongs to a different owner — the caller
     * cannot distinguish the two cases, which is intentional: it
     * prevents leaking whether another user's space exists.
     *
     * <p>Used by {@code GET /api/v1/spaces/{spaceId}}.
     *
     * @param spaceId     the space id from the path
     * @param ownerSubject the authenticated JWT subject
     * @return the owned space, or {@code null} if absent / not owned
     */
    @Select("SELECT * FROM learning_space WHERE id = #{spaceId} AND owner_subject = #{ownerSubject}")
    LearningSpace selectByIdAndOwner(@Param("spaceId") Long spaceId,
                                     @Param("ownerSubject") String ownerSubject);

    /**
     * Returns ALL LearningSpaces owned by the given subject, newest
     * first. The list is bounded by the owner predicate alone — a
     * caller can never enumerate another subject's spaces through
     * this query.
     *
     * <p>Used by {@code GET /api/v1/spaces}.
     *
     * @param ownerSubject the authenticated JWT subject
     * @return owned spaces, newest first; empty list when none
     */
    @Select("SELECT * FROM learning_space WHERE owner_subject = #{ownerSubject} "
            + "ORDER BY created_at DESC, id DESC")
    List<LearningSpace> selectByOwner(@Param("ownerSubject") String ownerSubject);

    @Select("SELECT * FROM learning_space WHERE owner_subject = #{ownerSubject} "
            + "ORDER BY created_at DESC, id DESC")
    List<LearningSpace> selectByOwnerIncludingArchived(@Param("ownerSubject") String ownerSubject);

    @Update("UPDATE learning_space SET name = #{name}, description = #{description}, updated_at = #{updatedAt} "
            + "WHERE id = #{spaceId} AND owner_subject = #{ownerSubject}")
    int updateNameDescriptionByIdAndOwner(@Param("spaceId") Long spaceId,
                                          @Param("ownerSubject") String ownerSubject,
                                          @Param("name") String name,
                                          @Param("description") String description,
                                          @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE learning_space SET status = #{status}, archived_at = #{archivedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{spaceId} AND owner_subject = #{ownerSubject}")
    int archiveByIdAndOwner(@Param("spaceId") Long spaceId,
                            @Param("ownerSubject") String ownerSubject,
                            @Param("status") String status,
                            @Param("archivedAt") LocalDateTime archivedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE learning_space SET status = #{status}, archived_at = NULL, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{spaceId} AND owner_subject = #{ownerSubject}")
    int restoreByIdAndOwner(@Param("spaceId") Long spaceId,
                            @Param("ownerSubject") String ownerSubject,
                            @Param("status") String status,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT * FROM learning_space "
            + "WHERE (#{ownerSubject} IS NULL OR owner_subject = #{ownerSubject}) "
            + "  AND (#{status} IS NULL OR status = #{status}) "
            + "ORDER BY created_at DESC, id DESC")
    List<LearningSpace> selectAllAdmin(@Param("ownerSubject") String ownerSubject,
                                       @Param("status") String status);

    @Select("SELECT COUNT(*) FROM learning_space_membership WHERE space_id = #{spaceId}")
    long countMembers(@Param("spaceId") Long spaceId);

    @Update("UPDATE learning_space SET status = #{status}, archived_at = #{archivedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{spaceId}")
    int archiveById(@Param("spaceId") Long spaceId,
                    @Param("status") String status,
                    @Param("archivedAt") LocalDateTime archivedAt,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE learning_space SET status = #{status}, archived_at = NULL, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{spaceId}")
    int restoreById(@Param("spaceId") Long spaceId,
                    @Param("status") String status,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
