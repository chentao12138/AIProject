package com.aistudy.server.source.page.mapper;

import com.aistudy.server.source.page.entity.SourcePage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-006 — production MyBatis-Plus mapper for {@link SourcePage}.
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
public interface SourcePageMapper extends BaseMapper<SourcePage> {

    /**
     * Lists pages of one owned source in final reading order. The
     * owner predicate and the source↔space consistency are enforced
     * IN SQL — this method can never list pages of a source the
     * caller does not own.
     */
    @Select("SELECT sp.* "
            + "FROM source_page sp "
            + "JOIN source s "
            + "  ON s.id = sp.source_id "
            + " AND s.space_id = sp.space_id "
            + "JOIN learning_space ls ON ls.id = sp.space_id "
            + "WHERE sp.space_id = #{spaceId} "
            + "  AND sp.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sp.page_order ASC, sp.id ASC")
    List<SourcePage> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                              @Param("sourceId") Long sourceId,
                                              @Param("ownerSubject") String ownerSubject);

    /**
     * Deletes all pages of one source asset. Used by PDF ingestion
     * to replace derived content atomically on retry.
     */
    @Delete("DELETE FROM source_page "
            + "WHERE space_id = #{spaceId} "
            + "  AND source_id = #{sourceId} "
            + "  AND source_asset_id = #{assetId}")
    void deleteBySpaceSourceAsset(@Param("spaceId") Long spaceId,
                                 @Param("sourceId") Long sourceId,
                                 @Param("assetId") Long assetId);
}
