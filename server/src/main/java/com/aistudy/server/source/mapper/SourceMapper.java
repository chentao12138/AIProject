package com.aistudy.server.source.mapper;

import com.aistudy.server.source.entity.Source;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-002 — production MyBatis-Plus mapper for {@link Source}.
 *
 * <p>Extends {@link BaseMapper} for insert; all READS are explicit
 * owner-scoped {@code @Select} queries that JOIN through
 * {@code learning_space} so the owner subject is enforced in SQL.
 *
 * <h3>Why JOIN-based reads (IDOR protection)</h3>
 *
 * A Source's ownership is derived from its parent LearningSpace
 * (V005 has no owner_subject column by design). Every read therefore
 * joins {@code learning_space} and constrains
 * {@code ls.owner_subject = ?}:
 *
 * <ul>
 *   <li>{@link #selectByIdAndSpaceAndOwner} — used by
 *       {@code GET /api/v1/spaces/{spaceId}/sources/{sourceId}}:
 *       {@code WHERE s.id = ? AND s.space_id = ? AND
 *       ls.owner_subject = ?}. This single query resists BOTH
 *       "user2 guesses user1's spaceId/sourceId" AND "correct owner
 *       but sourceId actually belongs to another space" — all three
 *       mismatches collapse to "no row".</li>
 *   <li>{@link #selectBySpaceId} — used by the list endpoint AFTER
 *       the controller/service has already proven the parent space
 *       is owned by the caller (owner-scoped parent check). The
 *       list query itself is space-scoped; the owner boundary was
 *       established by the parent check in the same request.</li>
 * </ul>
 *
 * <p>Note: {@link BaseMapper} still exposes unscoped methods
 * ({@code selectById(id)}, {@code selectList(...)},
 * {@code selectOne(...)}) — they are inherited and MUST NOT be
 * called by the service layer, because they carry no space/owner
 * predicate. Only {@link com.aistudy.server.source.service.SourceService}
 * calls this mapper, and it never uses the unscoped inherited
 * methods.
 */
@Mapper
public interface SourceMapper extends BaseMapper<Source> {

    /**
     * Returns the source identified by {@code id}, but ONLY IF it
     * belongs to {@code spaceId} AND that space is owned by
     * {@code ownerSubject}. Any mismatch — wrong id, wrong space,
     * wrong owner — returns {@code null}, so callers cannot
     * distinguish "source does not exist" from "source exists but
     * you may not see it" (anti-probing).
     *
     * @param sourceId     the source id from the path
     * @param spaceId      the parent space id from the path
     * @param ownerSubject the authenticated JWT subject
     * @return the owned source, or {@code null}
     */
    @Select("SELECT s.* "
            + "FROM source s "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE s.id = #{sourceId} "
            + "  AND s.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    Source selectByIdAndSpaceAndOwner(@Param("sourceId") Long sourceId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);

    /**
     * Returns all sources of one space, newest first. The caller
     * MUST have already proven (owner-scoped) that {@code spaceId}
     * belongs to the current user; this query is space-scoped only.
     *
     * @param spaceId the parent space id from the path
     * @return sources of the space, newest first
     */
    @Select("SELECT * FROM source "
            + "WHERE space_id = #{spaceId} "
            + "ORDER BY created_at DESC, id DESC")
    List<Source> selectBySpaceId(@Param("spaceId") Long spaceId);
}
