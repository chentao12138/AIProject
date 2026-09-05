package com.aistudy.server.knowledge.point.mapper;

import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * BUSINESS-003 — production MyBatis-Plus mapper for
 * {@link KnowledgePoint}.
 *
 * <p>{@link BaseMapper} is used ONLY for insert (create). All
 * business READS are explicit owner-scoped {@code @Select}s that
 * JOIN {@code learning_space} and constrain {@code ls.owner_subject};
 * all reads also filter {@code deleted_at IS NULL} (soft-delete rule).
 *
 * <ul>
 *   <li>{@link #selectByIdSpaceOwner} — single point:
 *       {@code WHERE kp.id = ? AND kp.space_id = ? AND
 *       ls.owner_subject = ? AND kp.deleted_at IS NULL}. Resists
 *       cross-space and cross-owner IDOR — one row or {@code null}.</li>
 *   <li>{@link #selectBySpaceOwner} — list points of one owned space,
 *       newest first, excluding soft-deleted rows.</li>
 *   <li>{@link #publishByIdAndSpace} — owner-scoped UPDATE:
 *       {@code UPDATE knowledge_point SET status = 'PUBLISHED',
 *       published_at = NOW(6), updated_at = NOW(6) WHERE id = ?
 *       AND space_id = ? AND deleted_at IS NULL}. The owner predicate
 *       is carried by the caller's {@code spaceId} AFTER the
 *       controller/service already proved the space belongs to the
 *       current subject in the same transaction; the WHERE also
 *       re-checks {@code space_id} so a wrong-path spaceId updates
 *       nothing.</li>
 * </ul>
 *
 * <p>The inherited unscoped BaseMapper reads are NEVER used. Only
 * {@link com.aistudy.server.knowledge.point.service.KnowledgePointService}
 * calls this mapper.
 */
@Mapper
public interface KnowledgePointMapper extends BaseMapper<KnowledgePoint> {

    /**
     * Returns the point iff it belongs to {@code spaceId}, that space
     * is owned by {@code ownerSubject}, and the row is not
     * soft-deleted. {@code null} otherwise (404, anti-probing).
     */
    @Select("SELECT kp.* "
            + "FROM knowledge_point kp "
            + "JOIN learning_space ls ON ls.id = kp.space_id "
            + "WHERE kp.id = #{knowledgePointId} "
            + "  AND kp.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND kp.deleted_at IS NULL")
    KnowledgePoint selectByIdSpaceOwner(@Param("knowledgePointId") Long knowledgePointId,
                                        @Param("spaceId") Long spaceId,
                                        @Param("ownerSubject") String ownerSubject);

    /**
     * Lists non-deleted points of one owned space, newest first. The
     * owner predicate is enforced IN SQL via the JOIN — this method
     * can never list points of an unowned space even if a caller
     * bypasses the service layer.
     */
    @Select("SELECT kp.* "
            + "FROM knowledge_point kp "
            + "JOIN learning_space ls ON ls.id = kp.space_id "
            + "WHERE kp.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND kp.deleted_at IS NULL "
            + "ORDER BY kp.created_at DESC, kp.id DESC")
    List<KnowledgePoint> selectBySpaceOwner(@Param("spaceId") Long spaceId,
                                            @Param("ownerSubject") String ownerSubject);

    /**
     * Owner-scoped publish UPDATE. Updates exactly the row identified
     * by id + space_id (spaceId was already proven owned by the
     * caller in the same transaction); soft-deleted rows are never
     * published. Returns the number of rows updated (0 → 404).
     *
     * <p>No {@code NOW(6)} server-side default is used for
     * {@code published_at} here because the service wants to return
     * the exact timestamp in the response; the service passes
     * {@code publishedAt} explicitly.
     */
    @Update("UPDATE knowledge_point "
            + "SET status = #{status}, "
            + "    published_at = #{publishedAt}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{knowledgePointId} "
            + "  AND space_id = #{spaceId} "
            + "  AND deleted_at IS NULL")
    int publishByIdAndSpace(@Param("knowledgePointId") Long knowledgePointId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("publishedAt") java.time.LocalDateTime publishedAt,
                            @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
