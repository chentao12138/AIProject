package com.aistudy.server.source.content.mapper;

import com.aistudy.server.source.content.entity.ContentBlock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-006 — production MyBatis-Plus mapper for {@link ContentBlock}.
 *
 * <p>Extends {@link BaseMapper} for insert only (extraction writes go
 * through {@link com.aistudy.server.ingestion.extract.ContentExtractionService}).
 * All READS are explicit owner-scoped {@code @Select} queries that
 * JOIN through {@code source} AND {@code learning_space} so the owner
 * subject and the source↔space consistency are enforced in SQL
 * (BUSINESS-004 pattern). Inherited unscoped BaseMapper reads are
 * NEVER used by the service layer.
 */
@Mapper
public interface ContentBlockMapper extends BaseMapper<ContentBlock> {

    /**
     * Lists all blocks of one owned source in document order. The
     * owner predicate and the source↔space consistency are enforced
     * IN SQL — this method can never list blocks of a source the
     * caller does not own.
     */
    @Select("SELECT cb.* "
            + "FROM content_block cb "
            + "JOIN source s "
            + "  ON s.id = cb.source_id "
            + " AND s.space_id = cb.space_id "
            + "JOIN learning_space ls ON ls.id = cb.space_id "
            + "WHERE cb.space_id = #{spaceId} "
            + "  AND cb.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY cb.sort_order ASC, cb.id ASC")
    List<ContentBlock> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                                @Param("sourceId") Long sourceId,
                                                @Param("ownerSubject") String ownerSubject);

    /**
     * Lists blocks of one owned source scoped to ONE page of that
     * source. The JOIN also requires the page to really belong to
     * the source and the source to the space — a page id from
     * another source simply yields no rows.
     */
    @Select("SELECT cb.* "
            + "FROM content_block cb "
            + "JOIN source_page sp "
            + "  ON sp.id = cb.source_page_id "
            + " AND sp.space_id = cb.space_id "
            + " AND sp.source_id = cb.source_id "
            + "JOIN source s "
            + "  ON s.id = cb.source_id "
            + " AND s.space_id = cb.space_id "
            + "JOIN learning_space ls ON ls.id = cb.space_id "
            + "WHERE cb.space_id = #{spaceId} "
            + "  AND cb.source_id = #{sourceId} "
            + "  AND cb.source_page_id = #{pageId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY cb.sort_order ASC, cb.id ASC")
    List<ContentBlock> selectBySpaceSourcePageOwner(@Param("spaceId") Long spaceId,
                                                    @Param("sourceId") Long sourceId,
                                                    @Param("pageId") Long pageId,
                                                    @Param("ownerSubject") String ownerSubject);

    /**
     * Returns ONE block iff ALL of: it exists, belongs to
     * {@code spaceId}, its source really belongs to that space
     * (JOIN), and the space is owned by {@code ownerSubject}. Any
     * mismatch returns {@code null} (404, anti-probing). Used by the
     * provenance service to validate a ContentBlock endpoint of a
     * KnowledgePointSource link WITHOUT knowing its source id.
     */
    @Select("SELECT cb.* "
            + "FROM content_block cb "
            + "JOIN source s "
            + "  ON s.id = cb.source_id "
            + " AND s.space_id = cb.space_id "
            + "JOIN learning_space ls ON ls.id = cb.space_id "
            + "WHERE cb.id = #{blockId} "
            + "  AND cb.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    ContentBlock selectByIdSpaceOwner(@Param("blockId") Long blockId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);
}
