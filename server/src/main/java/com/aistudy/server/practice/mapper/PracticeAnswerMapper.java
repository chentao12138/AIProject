package com.aistudy.server.practice.mapper;

import com.aistudy.server.practice.entity.PracticeAnswer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-010 — mapper for {@link PracticeAnswer}.
 *
 * <p>Space-scoped reads; scoped update for the upsert path.
 */
@Mapper
public interface PracticeAnswerMapper extends BaseMapper<PracticeAnswer> {

    /** The latest answer of one slot (0..1 rows). */
    @Select("SELECT * FROM practice_answer "
            + "WHERE space_id = #{spaceId} AND practice_session_question_id = #{slotId} LIMIT 1")
    PracticeAnswer selectBySlotId(@Param("spaceId") Long spaceId,
                                  @Param("slotId") Long slotId);

    /** All answers of one session (JOIN slots), for submit summary. */
    @Select("SELECT pa.* FROM practice_answer pa "
            + "JOIN practice_session_question psq ON psq.id = pa.practice_session_question_id "
            + "WHERE pa.space_id = #{spaceId} AND psq.practice_session_id = #{sessionId}")
    List<PracticeAnswer> selectBySessionId(@Param("spaceId") Long spaceId,
                                           @Param("sessionId") Long sessionId);

    /** Scoped update for re-answering (upsert) while IN_PROGRESS. */
    @Update("UPDATE practice_answer "
            + "SET answer_data_json = #{answerDataJson}, "
            + "    is_correct = #{isCorrect}, "
            + "    score = #{score}, "
            + "    submitted_at = #{submittedAt}, "
            + "    duration_ms = #{durationMs}, "
            + "    feedback_json = #{feedbackJson}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND space_id = #{spaceId} AND user_subject = #{userSubject}")
    int updateByIdAndSpace(@Param("id") Long id,
                           @Param("spaceId") Long spaceId,
                           @Param("userSubject") String userSubject,
                           @Param("answerDataJson") String answerDataJson,
                           @Param("isCorrect") Boolean isCorrect,
                           @Param("score") Integer score,
                           @Param("submittedAt") LocalDateTime submittedAt,
                           @Param("durationMs") Integer durationMs,
                           @Param("feedbackJson") String feedbackJson,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /** Re-grading fix-up at submit (objective rows only). */
    @Update("UPDATE practice_answer "
            + "SET is_correct = #{isCorrect}, score = #{score}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND space_id = #{spaceId}")
    int updateGradeByIdAndSpace(@Param("id") Long id,
                                @Param("spaceId") Long spaceId,
                                @Param("isCorrect") Boolean isCorrect,
                                @Param("score") Integer score,
                                @Param("updatedAt") LocalDateTime updatedAt);
}
