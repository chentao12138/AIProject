package com.aistudy.server.note.mapper;

import com.aistudy.server.note.entity.NoteKnowledgePoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NoteKnowledgePointMapper extends BaseMapper<NoteKnowledgePoint> {

    @Select("SELECT nkp.knowledge_point_id FROM note_knowledge_point nkp "
            + "JOIN learning_space ls ON ls.id = nkp.space_id "
            + "WHERE nkp.note_id = #{noteId} AND nkp.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    List<Long> selectKnowledgePointIdsByNote(@Param("noteId") Long noteId,
                                             @Param("spaceId") Long spaceId,
                                             @Param("ownerSubject") String ownerSubject);

    @Select("<script>"
            + "SELECT knowledge_point_id FROM note_knowledge_point "
            + "WHERE note_id = #{noteId} "
            + "  AND knowledge_point_id IN "
            + "  <foreach collection='kpIds' item='id' open='(' separator=',' close=')'>"
            + "    #{id}"
            + "  </foreach>"
            + "</script>")
    List<Long> selectExistingKpIds(@Param("noteId") Long noteId,
                                   @Param("kpIds") List<Long> kpIds);

    @Delete("DELETE FROM note_knowledge_point WHERE note_id = #{noteId} AND knowledge_point_id = #{kpId}")
    int deleteByNoteIdAndKpId(@Param("noteId") Long noteId,
                              @Param("kpId") Long kpId);
}
