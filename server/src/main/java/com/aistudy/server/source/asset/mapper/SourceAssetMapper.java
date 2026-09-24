package com.aistudy.server.source.asset.mapper;

import com.aistudy.server.source.asset.entity.SourceAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SourceAssetMapper extends BaseMapper<SourceAsset> {

    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.id = #{assetId} AND sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    SourceAsset selectByIdSpaceSourceOwner(@Param("assetId") Long assetId,
                                           @Param("spaceId") Long spaceId,
                                           @Param("sourceId") Long sourceId,
                                           @Param("ownerSubject") String ownerSubject);

    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sa.created_at DESC, sa.id DESC")
    List<SourceAsset> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                                @Param("sourceId") Long sourceId,
                                                @Param("ownerSubject") String ownerSubject);

    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} AND sa.sha256 = #{sha256} AND ls.owner_subject = #{ownerSubject}")
    SourceAsset selectBySpaceSourceAndSha256(@Param("spaceId") Long spaceId,
                                             @Param("sourceId") Long sourceId,
                                             @Param("sha256") String sha256,
                                             @Param("ownerSubject") String ownerSubject);

    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.space_id = #{spaceId} AND sa.sha256 = #{sha256} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sa.created_at ASC, sa.id ASC LIMIT 1")
    SourceAsset selectBySpaceAndSha256(@Param("spaceId") Long spaceId,
                                       @Param("sha256") String sha256,
                                       @Param("ownerSubject") String ownerSubject);

    @Select("SELECT storage_key FROM source_asset WHERE storage_key IN (#{storageKeys})")
    List<String> selectExistingStorageKeys(@Param("storageKeys") List<String> storageKeys);

    @Select("SELECT sa.* FROM source_asset sa "
            + "WHERE sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} "
            + "ORDER BY sa.created_at ASC, sa.id ASC")
    List<SourceAsset> selectBySpaceSource(@Param("spaceId") Long spaceId,
                                          @Param("sourceId") Long sourceId);

    /**
     * One asset pinned to both its source and space (ADMIN raw-bytes read;
     * no owner predicate because governance crosses owners by design).
     */
    @Select("SELECT sa.* FROM source_asset sa "
            + "WHERE sa.id = #{assetId} AND sa.source_id = #{sourceId} AND sa.space_id = #{spaceId}")
    SourceAsset selectByIdSpaceSource(@Param("assetId") Long assetId,
                                      @Param("sourceId") Long sourceId,
                                      @Param("spaceId") Long spaceId);
}
