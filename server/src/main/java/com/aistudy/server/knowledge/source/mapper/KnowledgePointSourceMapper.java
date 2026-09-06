package com.aistudy.server.knowledge.source.mapper;

import com.aistudy.server.knowledge.source.entity.KnowledgePointSource;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-007 — production MyBatis-Plus mapper for
 * {@link KnowledgePointSource}.
 *
 * <p>Extends {@link BaseMapper} for insert only. All READS are
 * explicit owner-scoped {@code @Select} queries that JOIN through
 * {@code knowledge_point} → {@code learning_space} (and
 * {@code content_block} → {@code source} → {@code learning_space})
 * so the owner subject and the same-space invariant are enforced in
 * SQL:
 *
 * <ul>
 *   <li>{@link #selectBySpacePointOwner} — links of one owned point
 *       (JOIN on both endpoints + owner predicate)</li>
 *   <li>{@link #selectExistingBlockIds} — dedup pre-check: which of
 *       the requested block ids are already linked to this point
 *       (same space predicates in SQL)</li>
 * </ul>
 *
 * <p>Inherited unscoped BaseMapper reads are NEVER used by the
 * service layer.
 */
@Mapper
public interface KnowledgePointSourceMapper extends BaseMapper<KnowledgePointSource> {

    /**
     * Lists the provenance links of one owned, non-deleted point.
     * The JOIN requires the point to belong to the space AND the
     * block to belong to the same space; the owner predicate comes
     * from {@code learning_space}. A point (or block) from another
     * space yields no rows — nothing leaks.
     */
    @Select("SELECT kps.* "
            + "FROM knowledge_point_source kps "
            + "JOIN knowledge_point kp "
            + "  ON kp.id = kps.knowledge_point_id "
            + " AND kp.space_id = kps.space_id "
            + " AND kp.deleted_at IS NULL "
            + "JOIN content_block cb "
            + "  ON cb.id = kps.content_block_id "
            + " AND cb.space_id = kps.space_id "
            + "JOIN learning_space ls ON ls.id = kps.space_id "
            + "WHERE kps.space_id = #{spaceId} "
            + "  AND kps.knowledge_point_id = #{knowledgePointId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY kps.id ASC")
    List<KnowledgePointSource> selectBySpacePointOwner(
            @Param("spaceId") Long spaceId,
            @Param("knowledgePointId") Long knowledgePointId,
            @Param("ownerSubject") String ownerSubject);

    /**
     * Dedup pre-check: returns the subset of {@code contentBlockIds}
     * already linked to this point in this space. All predicates are
     * space-scoped; the caller already proved ownership of the point.
     */
    @Select("<script>"
            + "SELECT content_block_id FROM knowledge_point_source "
            + "WHERE space_id = #{spaceId} "
            + "  AND knowledge_point_id = #{knowledgePointId} "
            + "  AND content_block_id IN "
            + "  <foreach collection='contentBlockIds' item='bid' open='(' separator=',' close=')'>"
            + "    #{bid}"
            + "  </foreach>"
            + "</script>")
    List<Long> selectExistingBlockIds(@Param("spaceId") Long spaceId,
                                      @Param("knowledgePointId") Long knowledgePointId,
                                      @Param("contentBlockIds") List<Long> contentBlockIds);
}
