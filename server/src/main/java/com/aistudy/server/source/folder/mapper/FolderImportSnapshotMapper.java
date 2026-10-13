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

    /**
     * F-14 — the previous manifest to diff a new submission against:
     * the newest snapshot of this (space, source) that represents a
     * real folder state (COMPLETED or AWAITING_UPLOAD — a DRAFT has no
     * entries yet, a FAILED one never validated its manifest),
     * excluding the snapshot being submitted.
     */
    @Select("SELECT fs.* FROM folder_import_snapshot fs "
            + "JOIN learning_space ls ON ls.id = fs.space_id "
            + "WHERE fs.space_id = #{spaceId} AND fs.source_id = #{sourceId} "
            + "  AND fs.id <> #{excludeSnapshotId} "
            + "  AND fs.status IN ('COMPLETED', 'AWAITING_UPLOAD') "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY fs.created_at DESC, fs.id DESC LIMIT 1")
    FolderImportSnapshot selectLatestProcessed(@Param("spaceId") Long spaceId,
                                               @Param("sourceId") Long sourceId,
                                               @Param("excludeSnapshotId") Long excludeSnapshotId,
                                               @Param("ownerSubject") String ownerSubject);
}
