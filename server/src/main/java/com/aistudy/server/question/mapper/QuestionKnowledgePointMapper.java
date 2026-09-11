package com.aistudy.server.question.mapper;

import com.aistudy.server.question.entity.QuestionKnowledgePoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-008 — mapper for {@link QuestionKnowledgePoint}.
 *
 * <p>Space-scoped reads; insert via {@link BaseMapper} after same-space
 * validation. Question lists the points it tests; Mastery (014)
 * resolves a point's questions through {@link #selectQuestionIdsByKnowledgePointId}.
 */
@Mapper
public interface QuestionKnowledgePointMapper extends BaseMapper<QuestionKnowledgePoint> {

    @Select("SELECT * FROM question_knowledge_point "
            + "WHERE space_id = #{spaceId} AND question_id = #{questionId} "
            + "ORDER BY id ASC")
    List<QuestionKnowledgePoint> selectByQuestionId(@Param("spaceId") Long spaceId,
                                                    @Param("questionId") Long questionId);

    @Select("SELECT question_id FROM question_knowledge_point "
            + "WHERE space_id = #{spaceId} AND knowledge_point_id = #{knowledgePointId} "
            + "ORDER BY id ASC")
    List<Long> selectQuestionIdsByKnowledgePointId(@Param("spaceId") Long spaceId,
                                                   @Param("knowledgePointId") Long knowledgePointId);

    /** The knowledge points one question tests (mastery recompute). */
    @Select("SELECT knowledge_point_id FROM question_knowledge_point "
            + "WHERE space_id = #{spaceId} AND question_id = #{questionId} "
            + "ORDER BY id ASC")
    List<Long> selectKnowledgePointIdsByQuestionId(@Param("spaceId") Long spaceId,
                                                   @Param("questionId") Long questionId);
}
