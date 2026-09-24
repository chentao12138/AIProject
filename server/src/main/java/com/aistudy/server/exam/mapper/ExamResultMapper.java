package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamResult;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * BUSINESS-013 — mapper for {@link ExamResult}.
 *
 * <p>Immutable history; one row per attempt.
 */
@Mapper
public interface ExamResultMapper extends BaseMapper<ExamResult> {

    @Select("SELECT * FROM exam_result WHERE space_id = #{spaceId} "
            + "AND exam_attempt_id = #{attemptId} LIMIT 1")
    ExamResult selectByAttemptId(@Param("spaceId") Long spaceId,
                                 @Param("attemptId") Long attemptId);

    @org.apache.ibatis.annotations.Update(
            "UPDATE exam_result SET score = #{score}, max_score = #{maxScore}, "
                    + "correct_count = #{correctCount}, wrong_count = #{wrongCount}, "
                    + "unanswered_count = #{unansweredCount} "
                    + "WHERE id = #{id} AND space_id = #{spaceId}")
    int updateScores(@Param("id") Long id,
                     @Param("spaceId") Long spaceId,
                     @Param("score") Integer score,
                     @Param("maxScore") Integer maxScore,
                     @Param("correctCount") Integer correctCount,
                     @Param("wrongCount") Integer wrongCount,
                     @Param("unansweredCount") Integer unansweredCount);
}
