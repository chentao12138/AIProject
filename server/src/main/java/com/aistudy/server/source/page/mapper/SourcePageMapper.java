package com.aistudy.server.source.page.mapper;

import com.aistudy.server.source.page.entity.SourcePage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SourcePageMapper extends BaseMapper<SourcePage> {

    @Select("SELECT sp.* FROM source_page sp "
            + "JOIN learning_space ls ON ls.id = sp.space_id "
            + "WHERE sp.source_id = #{sourceId} AND sp.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sp.page_order ASC, sp.id ASC")
    List<SourcePage> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                              @Param("sourceId") Long sourceId,
                                              @Param("ownerSubject") String ownerSubject);

    @Update("UPDATE source_page SET page_order = #{pageOrder}, page_type = #{pageType}, updated_at = #{updatedAt} "
            + "WHERE id = #{pageId} AND space_id = #{spaceId}")
    int updateOrderByIdAndSpace(@Param("pageId") Long pageId,
                                @Param("spaceId") Long spaceId,
                                @Param("pageOrder") Integer pageOrder,
                                @Param("pageType") String pageType,
                                @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Owner-scoped reorder write: MUST also constrain source_id so a
     * page from another source in the same space cannot be moved.
     */
    @Update("UPDATE source_page SET page_order = #{pageOrder}, page_type = #{pageType}, updated_at = #{updatedAt} "
            + "WHERE id = #{pageId} AND space_id = #{spaceId} AND source_id = #{sourceId}")
    int updateOrderByIdSpaceSource(@Param("pageId") Long pageId,
                                   @Param("spaceId") Long spaceId,
                                   @Param("sourceId") Long sourceId,
                                   @Param("pageOrder") Integer pageOrder,
                                   @Param("pageType") String pageType,
                                   @Param("updatedAt") LocalDateTime updatedAt);

    /** Mark human-confirmed order after a successful reorder batch. */
    @Update("UPDATE source_page SET order_status = 'CONFIRMED', updated_at = #{updatedAt} "
            + "WHERE id = #{pageId} AND space_id = #{spaceId} AND source_id = #{sourceId}")
    int confirmOrderByIdSpaceSource(@Param("pageId") Long pageId,
                                    @Param("spaceId") Long spaceId,
                                    @Param("sourceId") Long sourceId,
                                    @Param("updatedAt") LocalDateTime updatedAt);

    @Update("DELETE FROM source_page WHERE space_id = #{spaceId} AND source_id = #{sourceId} AND source_asset_id = #{sourceAssetId} "
            + "AND (extraction_revision_id IS NULL OR extraction_revision_id IN ("
            + "  SELECT id FROM (SELECT id FROM extraction_revision WHERE source_id = #{sourceId} AND status IN ('DRAFT','REJECTED')) t))")
    int deleteBySpaceSourceAsset(@Param("spaceId") Long spaceId,
                                  @Param("sourceId") Long sourceId,
                                  @Param("sourceAssetId") Long sourceAssetId);

    /** Assign newly extracted pages to the current draft revision. */
    @Update("UPDATE source_page SET extraction_revision_id = #{revisionId}, updated_at = #{updatedAt} "
            + "WHERE space_id = #{spaceId} AND source_id = #{sourceId} "
            + "  AND (extraction_revision_id IS NULL OR extraction_revision_id = #{revisionId})")
    int stampRevisionOnSourcePages(@Param("spaceId") Long spaceId,
                                   @Param("sourceId") Long sourceId,
                                   @Param("revisionId") Long revisionId,
                                   @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT sp.* FROM source_page sp "
            + "JOIN learning_space ls ON ls.id = sp.space_id "
            + "WHERE sp.source_asset_id = #{sourceAssetId} AND sp.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sp.page_order ASC, sp.id ASC")
    List<SourcePage> selectBySpaceSourceAssetOwner(@Param("sourceAssetId") Long sourceAssetId,
                                                   @Param("spaceId") Long spaceId,
                                                   @Param("ownerSubject") String ownerSubject);

    /** Pages of one extraction revision (version compare / provenance). */
    @Select("SELECT sp.* FROM source_page sp "
            + "WHERE sp.extraction_revision_id = #{revisionId} AND sp.space_id = #{spaceId} "
            + "ORDER BY sp.page_order ASC, sp.id ASC")
    List<SourcePage> selectByRevision(@Param("revisionId") Long revisionId,
                                      @Param("spaceId") Long spaceId);

    /** Single page inside one space (ADMIN governance read; no owner predicate). */
    @Select("SELECT sp.* FROM source_page sp WHERE sp.id = #{pageId} AND sp.space_id = #{spaceId}")
    SourcePage selectByIdAndSpace(@Param("pageId") Long pageId,
                                  @Param("spaceId") Long spaceId);

    /**
     * ADMIN reorder: page order and the human-confirmed marker move together,
     * so a reordered page can never be left AUTO-ordered with the new position.
     */
    @Update("UPDATE source_page SET page_order = #{pageOrder}, order_status = 'CONFIRMED', "
            + "updated_at = #{updatedAt} WHERE id = #{pageId} AND space_id = #{spaceId}")
    int updateOrderAndConfirmByIdAndSpace(@Param("pageId") Long pageId,
                                          @Param("spaceId") Long spaceId,
                                          @Param("pageOrder") Integer pageOrder,
                                          @Param("updatedAt") LocalDateTime updatedAt);
}
