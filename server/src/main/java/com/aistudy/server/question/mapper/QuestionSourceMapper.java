package com.aistudy.server.question.mapper;

import com.aistudy.server.question.entity.QuestionSource;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuestionSourceMapper extends BaseMapper<QuestionSource> {

    @Select("SELECT qs.content_block_id FROM question_source qs "
            + "JOIN learning_space ls ON ls.id = qs.space_id "
            + "WHERE qs.question_id = #{questionId} AND qs.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    List<Long> selectBlockIdsByQuestion(@Param("questionId") Long questionId,
                                       @Param("spaceId") Long spaceId,
                                       @Param("ownerSubject") String ownerSubject);

    @Select("SELECT qs.content_block_id FROM question_source qs "
            + "WHERE qs.question_id = #{questionId} AND qs.content_block_id IN (#{blockIds})")
    List<Long> selectExistingBlockIds(@Param("questionId") Long questionId,
                                      @Param("blockIds") List<Long> blockIds);

    @Delete("DELETE FROM question_source "
            + "WHERE question_id = #{questionId} AND content_block_id = #{blockId} "
            + "  AND space_id = #{spaceId}")
    int deleteByQuestionAndBlock(@Param("questionId") Long questionId,
                                 @Param("blockId") Long blockId,
                                 @Param("spaceId") Long spaceId);
}
