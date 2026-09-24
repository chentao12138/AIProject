package com.aistudy.server.source.folder.mapper;

import com.aistudy.server.source.folder.entity.FolderImportEntry;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FolderImportEntryMapper extends BaseMapper<FolderImportEntry> {

    @Select("SELECT fe.* FROM folder_import_entry fe "
            + "JOIN folder_import_snapshot fs ON fs.id = fe.snapshot_id "
            + "JOIN learning_space ls ON ls.id = fs.space_id "
            + "WHERE fe.snapshot_id = #{snapshotId} AND fs.space_id = #{spaceId} AND fs.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY fe.relative_path ASC, fe.id ASC")
    List<FolderImportEntry> selectBySnapshotAndSpace(@Param("snapshotId") Long snapshotId,
                                                     @Param("spaceId") Long spaceId,
                                                     @Param("sourceId") Long sourceId,
                                                     @Param("ownerSubject") String ownerSubject);

    /** Delete all entries of one snapshot (called after ownership check). */
    @Delete("DELETE FROM folder_import_entry WHERE snapshot_id = #{snapshotId}")
    int deleteBySnapshotId(@Param("snapshotId") Long snapshotId);
}
