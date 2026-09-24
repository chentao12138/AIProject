package com.aistudy.server.source.folder.mapper;

import com.aistudy.server.source.folder.entity.FolderImportSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FolderImportSnapshotMapper extends BaseMapper<FolderImportSnapshot> {

    @Select("SELECT fs.* FROM folder_import_snapshot fs "
            + "JOIN learning_space ls ON ls.id = fs.space_id "
            + "WHERE fs.id = #{snapshotId} AND fs.space_id = #{spaceId} AND fs.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    FolderImportSnapshot selectByIdAndSpace(@Param("snapshotId") Long snapshotId,
                                            @Param("spaceId") Long spaceId,
                                            @Param("sourceId") Long sourceId,
                                            @Param("ownerSubject") String ownerSubject);

    @Select("SELECT fs.* FROM folder_import_snapshot fs "
            + "JOIN learning_space ls ON ls.id = fs.space_id "
            + "WHERE fs.space_id = #{spaceId} AND fs.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY fs.created_at DESC, fs.id DESC")
    java.util.List<FolderImportSnapshot> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                                                  @Param("sourceId") Long sourceId,
                                                                  @Param("ownerSubject") String ownerSubject);
}
