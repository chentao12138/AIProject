package com.aistudy.server.source.content.mapper;

import com.aistudy.server.source.content.entity.ContentBlock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ContentBlockMapper extends BaseMapper<ContentBlock> {

    @Select("SELECT cb.* FROM content_block cb "
            + "JOIN learning_space ls ON ls.id = cb.space_id "
            + "WHERE cb.source_id = #{sourceId} AND cb.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY cb.sort_order ASC, cb.id ASC")
    List<ContentBlock> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                                @Param("sourceId") Long sourceId,
                                                @Param("ownerSubject") String ownerSubject);

    @Select("SELECT cb.* FROM content_block cb "
            + "JOIN learning_space ls ON ls.id = cb.space_id "
            + "WHERE cb.source_id = #{sourceId} AND cb.source_page_id = #{pageId} AND cb.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY cb.sort_order ASC, cb.id ASC")
    List<ContentBlock> selectBySpaceSourcePageOwner(@Param("spaceId") Long spaceId,
                                                    @Param("sourceId") Long sourceId,
                                                    @Param("pageId") Long pageId,
                                                    @Param("ownerSubject") String ownerSubject);

    @Select("SELECT cb.* FROM content_block cb "
            + "JOIN learning_space ls ON ls.id = cb.space_id "
            + "WHERE cb.id = #{blockId} AND cb.source_id = #{sourceId} AND cb.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    ContentBlock selectByIdAndSpaceSourceOwner(@Param("blockId") Long blockId,
                                               @Param("spaceId") Long spaceId,
                                               @Param("sourceId") Long sourceId,
                                               @Param("ownerSubject") String ownerSubject);

    @Select("SELECT cb.* FROM content_block cb "
            + "JOIN learning_space ls ON ls.id = cb.space_id "
            + "WHERE cb.id = #{blockId} AND cb.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    ContentBlock selectByIdSpaceOwner(@Param("blockId") Long blockId,
                                       @Param("spaceId") Long spaceId,
                                       @Param("ownerSubject") String ownerSubject);

    @Update("DELETE FROM content_block WHERE space_id = #{spaceId} AND source_id = #{sourceId} AND source_asset_id = #{sourceAssetId} "
            + "AND (extraction_revision_id IS NULL OR extraction_revision_id IN ("
            + "  SELECT id FROM (SELECT id FROM extraction_revision WHERE source_id = #{sourceId} AND status IN ('DRAFT','REJECTED')) t))")
    int deleteBySpaceSourceAsset(@Param("spaceId") Long spaceId,
                                  @Param("sourceId") Long sourceId,
                                  @Param("sourceAssetId") Long sourceAssetId);

    @Update("UPDATE content_block SET extraction_revision_id = #{revisionId}, updated_at = #{updatedAt} "
            + "WHERE space_id = #{spaceId} AND source_id = #{sourceId} "
            + "  AND (extraction_revision_id IS NULL OR extraction_revision_id = #{revisionId})")
    int stampRevisionOnSourceBlocks(@Param("spaceId") Long spaceId,
                                    @Param("sourceId") Long sourceId,
                                    @Param("revisionId") Long revisionId,
                                    @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT cb.* FROM content_block cb "
            + "WHERE cb.extraction_revision_id = #{revisionId} AND cb.space_id = #{spaceId} "
            + "ORDER BY cb.sort_order ASC, cb.id ASC")
    List<ContentBlock> selectByRevision(@Param("revisionId") Long revisionId,
                                        @Param("spaceId") Long spaceId);

    @Select("SELECT cb.* FROM content_block cb "
            + "JOIN learning_space ls ON ls.id = cb.space_id "
            + "WHERE cb.source_asset_id = #{sourceAssetId} AND cb.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY cb.sort_order ASC, cb.id ASC")
    List<ContentBlock> selectBySpaceSourceAssetOwner(@Param("sourceAssetId") Long sourceAssetId,
                                                     @Param("spaceId") Long spaceId,
                                                     @Param("ownerSubject") String ownerSubject);

    /** Single block inside one space (ADMIN governance read; no owner predicate). */
    @Select("SELECT cb.* FROM content_block cb WHERE cb.id = #{blockId} AND cb.space_id = #{spaceId}")
    ContentBlock selectByIdAndSpace(@Param("blockId") Long blockId,
                                    @Param("spaceId") Long spaceId);

    /**
     * Governance correction of extracted text and/or block type in one
     * statement: a null argument leaves that column untouched.
     */
    @Update("UPDATE content_block "
            + "SET normalized_text = COALESCE(#{normalizedText}, normalized_text), "
            + "    block_type = COALESCE(#{blockType}, block_type), updated_at = #{updatedAt} "
            + "WHERE id = #{blockId} AND space_id = #{spaceId}")
    int updateTextAndTypeByIdAndSpace(@Param("blockId") Long blockId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("normalizedText") String normalizedText,
                                      @Param("blockType") String blockType,
                                      @Param("updatedAt") LocalDateTime updatedAt);
}
