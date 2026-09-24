package com.aistudy.server.note.mapper;

import com.aistudy.server.note.entity.Note;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    /** Notes owned by the authenticated subject in the given space. */
    @Select("SELECT n.* FROM note n "
            + "JOIN learning_space ls ON ls.id = n.space_id "
            + "WHERE n.space_id = #{spaceId} AND n.user_subject_id = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY n.created_at DESC, n.id DESC")
    List<Note> selectBySpaceAndUser(@Param("spaceId") Long spaceId,
                                    @Param("userSubject") String userSubject,
                                    @Param("ownerSubject") String ownerSubject);

    @Select("SELECT n.* FROM note n "
            + "JOIN learning_space ls ON ls.id = n.space_id "
            + "WHERE n.id = #{noteId} AND n.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND n.user_subject_id = #{ownerSubject}")
    Note selectByIdAndSpaceAndOwner(@Param("noteId") Long noteId,
                                    @Param("spaceId") Long spaceId,
                                    @Param("ownerSubject") String ownerSubject);
}
