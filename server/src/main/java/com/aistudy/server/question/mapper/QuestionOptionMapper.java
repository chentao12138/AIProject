package com.aistudy.server.question.mapper;

import com.aistudy.server.question.entity.QuestionOption;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-008 — mapper for {@link QuestionOption}.
 *
 * <p>Space-scoped reads only; insert via {@link BaseMapper} after the
 * service proved the owning space. No option update/delete API in V1.
 */
@Mapper
public interface QuestionOptionMapper extends BaseMapper<QuestionOption> {

    /** Options of ONE question, display order. */
    @Select("SELECT * FROM question_option "
            + "WHERE space_id = #{spaceId} AND question_id = #{questionId} "
            + "ORDER BY sort_order ASC, id ASC")
    List<QuestionOption> selectByQuestionId(@Param("spaceId") Long spaceId,
                                            @Param("questionId") Long questionId);
}
