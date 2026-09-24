package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamAnswer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-013 — mapper for {@link ExamAnswer}.
 *
 * <p>Space-scoped; one row per (attempt, exam_question) — upsert.
 */
@Mapper
public interface ExamAnswerMapper extends BaseMapper<ExamAnswer> {

    /** Latest answer of one attempt slot (0..1). */
    @Select("SELECT * FROM exam_answer "
            + "WHERE space_id = #{spaceId} AND exam_attempt_id = #{attemptId} "
            + "  AND exam_question_id = #{examQuestionId} LIMIT 1")
    ExamAnswer selectByAttemptAndQuestion(@Param("spaceId") Long spaceId,
                                          @Param("attemptId") Long attemptId,
                                          @Param("examQuestionId") Long examQuestionId);

    /** All answers of one attempt (submit + result). */
    @Select("SELECT * FROM exam_answer "
            + "WHERE space_id = #{spaceId} AND exam_attempt_id = #{attemptId}")
    List<ExamAnswer> selectByAttemptId(@Param("spaceId") Long spaceId,
                                       @Param("attemptId") Long attemptId);

    /** Owner-scoped single answer for post-submit AI explanation (AI-007). */
    @Select("SELECT ea.* FROM exam_answer ea "
            + "JOIN learning_space ls ON ls.id = ea.space_id "
            + "WHERE ea.id = #{answerId} AND ea.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} LIMIT 1")
    ExamAnswer selectByIdSpaceOwnerUser(@Param("answerId") Long answerId,
                                        @Param("spaceId") Long spaceId,
                                        @Param("userSubject") String userSubject,
                                        @Param("ownerSubject") String ownerSubject);

    /** Single answer by id + space (grading endpoint). */
    @Select("SELECT * FROM exam_answer WHERE id = #{id} AND space_id = #{spaceId} LIMIT 1")
    ExamAnswer selectByIdAndSpace(@Param("id") Long id,
                                  @Param("spaceId") Long spaceId);

    /** Scoped upsert update. */
    @Update("UPDATE exam_answer SET answer_data_json = #{answerDataJson}, "
            + "score = #{score}, is_correct = #{isCorrect}, answered_at = #{answeredAt}, "
            + "grading_status = #{gradingStatus}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND space_id = #{spaceId}")
    int updateByIdAndSpace(@Param("id") Long id,
                           @Param("spaceId") Long spaceId,
                           @Param("answerDataJson") String answerDataJson,
                           @Param("score") Integer score,
                           @Param("isCorrect") Boolean isCorrect,
                           @Param("answeredAt") LocalDateTime answeredAt,
                           @Param("gradingStatus") String gradingStatus,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /** Manual grade update with audit trail (previous score/feedback snapshot). */
    @Update("UPDATE exam_answer SET "
            + "previous_score = COALESCE(previous_score, score), "
            + "score = #{score}, "
            + "grading_status = #{gradingStatus}, "
            + "previous_feedback = COALESCE(previous_feedback, feedback), "
            + "feedback = #{feedback}, "
            + "graded_by = #{gradedBy}, "
            + "graded_at = #{gradedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND space_id = #{spaceId}")
    int gradeUpdate(@Param("id") Long id,
                    @Param("spaceId") Long spaceId,
                    @Param("score") Integer score,
                    @Param("gradingStatus") String gradingStatus,
                    @Param("feedback") String feedback,
                    @Param("gradedBy") String gradedBy,
                    @Param("gradedAt") LocalDateTime gradedAt,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
