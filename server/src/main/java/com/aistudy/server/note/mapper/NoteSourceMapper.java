package com.aistudy.server.note.mapper;

import com.aistudy.server.note.entity.NoteSource;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NoteSourceMapper extends BaseMapper<NoteSource> {

    @Select("SELECT ns.content_block_id FROM note_source ns "
            + "JOIN learning_space ls ON ls.id = ns.space_id "
            + "WHERE ns.note_id = #{noteId} AND ns.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    List<Long> selectContentBlockIdsByNote(@Param("noteId") Long noteId,
                                           @Param("spaceId") Long spaceId,
                                           @Param("ownerSubject") String ownerSubject);

    @Select("<script>"
            + "SELECT content_block_id FROM note_source "
            + "WHERE note_id = #{noteId} "
            + "  AND content_block_id IN "
            + "  <foreach collection='blockIds' item='id' open='(' separator=',' close=')'>"
            + "    #{id}"
            + "  </foreach>"
            + "</script>")
    List<Long> selectExistingBlockIds(@Param("noteId") Long noteId,
                                      @Param("blockIds") List<Long> blockIds);

    @Delete("DELETE FROM note_source WHERE note_id = #{noteId} AND content_block_id = #{blockId}")
    int deleteByNoteIdAndBlockId(@Param("noteId") Long noteId,
                                 @Param("blockId") Long blockId);
}
