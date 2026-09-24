package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.dto.ExamStatisticsDto.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface ExamStatisticsMapper {

    @Select("<script>"
            + "SELECT COALESCE(SUM(er.duration_ms), 0) "
            + "FROM exam_result er "
            + "JOIN exam_attempt eat ON eat.id = er.exam_attempt_id "
            + "WHERE er.space_id = #{spaceId} "
            + "  AND eat.user_subject = #{userSubject} "
            + "  AND eat.status = 'SUBMITTED' "
            + "  AND eat.created_at &gt;= #{from} AND eat.created_at &lt; #{to}"
            + "</script>")
    Long selectLearningDurationMs(@Param("spaceId") Long spaceId,
                                  @Param("userSubject") String userSubject,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT "
            + "  COALESCE(SUM(CASE WHEN pa.is_correct = TRUE THEN 1 ELSE 0 END), 0) AS correct_count, "
            + "  COUNT(*) AS total_count "
            + "FROM practice_answer pa "
            + "JOIN practice_session_question psq ON psq.id = pa.practice_session_question_id "
            + "JOIN practice_session ps ON ps.id = psq.practice_session_id AND ps.space_id = pa.space_id "
            + "WHERE pa.space_id = #{spaceId} AND pa.user_subject = #{userSubject} "
            + "  AND ps.status = 'SUBMITTED' "
            + "  AND pa.answered_at &gt;= #{from} AND pa.answered_at &lt; #{to}"
            + "</script>")
    Map<String, Object> selectPracticeAccuracy(@Param("spaceId") Long spaceId,
                                               @Param("userSubject") String userSubject,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM wrong_question wq "
            + "JOIN learning_space ls ON ls.id = wq.space_id "
            + "WHERE wq.space_id = #{spaceId} "
            + "  AND wq.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND wq.created_at &gt;= #{from} AND wq.created_at &lt; #{to}"
            + "</script>")
    Long selectWrongQuestionCount(@Param("spaceId") Long spaceId,
                                  @Param("userSubject") String userSubject,
                                  @Param("ownerSubject") String ownerSubject,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM review_record rr "
            + "JOIN review_task rt ON rt.id = rr.review_task_id "
            + "WHERE rr.space_id = #{spaceId} AND rr.user_subject = #{userSubject} "
            + "  AND rr.completed_at &gt;= #{from} AND rr.completed_at &lt; #{to}"
            + "</script>")
    Long selectReviewCompleted(@Param("spaceId") Long spaceId,
                               @Param("userSubject") String userSubject,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM review_task rt "
            + "JOIN learning_space ls ON ls.id = rt.space_id "
            + "WHERE rt.space_id = #{spaceId} "
            + "  AND rt.user_subject = #{userSubject} "
            + "  AND rt.status = 'PENDING' "
            + "  AND ls.owner_subject = #{ownerSubject}"
            + "</script>")
    Long selectReviewPending(@Param("spaceId") Long spaceId,
                             @Param("userSubject") String userSubject,
                             @Param("ownerSubject") String ownerSubject);

    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM exam_attempt eat "
            + "JOIN learning_space ls ON ls.id = eat.space_id "
            + "WHERE eat.space_id = #{spaceId} "
            + "  AND eat.user_subject = #{userSubject} "
            + "  AND eat.status = 'SUBMITTED' "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND eat.created_at &gt;= #{from} AND eat.created_at &lt; #{to}"
            + "</script>")
    Long selectExamHistoryCount(@Param("spaceId") Long spaceId,
                                @Param("userSubject") String userSubject,
                                @Param("ownerSubject") String ownerSubject,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT COALESCE(AVG(er.score), 0) "
            + "FROM exam_result er "
            + "JOIN exam_attempt eat ON eat.id = er.exam_attempt_id "
            + "JOIN learning_space ls ON ls.id = er.space_id "
            + "WHERE er.space_id = #{spaceId} AND eat.user_subject = #{userSubject} "
            + "  AND eat.status = 'SUBMITTED' "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND eat.created_at &gt;= #{from} AND eat.created_at &lt; #{to}"
            + "</script>")
    Double selectAvgExamScore(@Param("spaceId") Long spaceId,
                              @Param("userSubject") String userSubject,
                              @Param("ownerSubject") String ownerSubject,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT COALESCE(AVG(er.max_score), 0) "
            + "FROM exam_result er "
            + "JOIN exam_attempt eat ON eat.id = er.exam_attempt_id "
            + "JOIN learning_space ls ON ls.id = er.space_id "
            + "WHERE er.space_id = #{spaceId} AND eat.user_subject = #{userSubject} "
            + "  AND eat.status = 'SUBMITTED' "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND eat.created_at &gt;= #{from} AND eat.created_at &lt; #{to}"
            + "</script>")
    Double selectAvgExamMaxScore(@Param("spaceId") Long spaceId,
                                 @Param("userSubject") String userSubject,
                                 @Param("ownerSubject") String ownerSubject,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT "
            + "  COALESCE(SUM(CASE WHEN m.mastery_score &lt; 0.3 THEN 1 ELSE 0 END), 0) AS low, "
            + "  COALESCE(SUM(CASE WHEN m.mastery_score &gt;= 0.3 AND m.mastery_score &lt; 0.6 THEN 1 ELSE 0 END), 0) AS medium_low, "
            + "  COALESCE(SUM(CASE WHEN m.mastery_score &gt;= 0.6 AND m.mastery_score &lt; 0.85 THEN 1 ELSE 0 END), 0) AS medium_high, "
            + "  COALESCE(SUM(CASE WHEN m.mastery_score &gt;= 0.85 THEN 1 ELSE 0 END), 0) AS high "
            + "FROM mastery m "
            + "JOIN learning_space ls ON ls.id = m.space_id "
            + "WHERE m.space_id = #{spaceId} AND m.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject}"
            + "</script>")
    Map<String, Object> selectMasteryDistribution(@Param("spaceId") Long spaceId,
                                                  @Param("userSubject") String userSubject,
                                                  @Param("ownerSubject") String ownerSubject);

    @Select("<script>"
            + "SELECT "
            + "  COALESCE(SUM(CASE WHEN m.mastery_score &lt; 0.3 THEN 1 ELSE 0 END), 0) AS low, "
            + "  COALESCE(SUM(CASE WHEN m.mastery_score &gt;= 0.3 AND m.mastery_score &lt; 0.6 THEN 1 ELSE 0 END), 0) AS medium_low, "
            + "  COALESCE(SUM(CASE WHEN m.mastery_score &gt;= 0.6 AND m.mastery_score &lt; 0.85 THEN 1 ELSE 0 END), 0) AS medium_high, "
            + "  COALESCE(SUM(CASE WHEN m.mastery_score &gt;= 0.85 THEN 1 ELSE 0 END), 0) AS high "
            + "FROM mastery m "
            + "JOIN learning_space ls ON ls.id = m.space_id "
            + "WHERE m.space_id = #{spaceId} AND m.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{userSubject} "
            + "  AND m.last_evidence_at &gt;= #{from} AND m.last_evidence_at &lt; #{to}"
            + "</script>")
    Map<String, Object> selectMasteryMovement(@Param("spaceId") Long spaceId,
                                              @Param("userSubject") String userSubject,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT qkp.knowledge_point_id AS knowledge_point_id, "
            + "  COUNT(*) AS total, "
            + "  COALESCE(SUM(CASE WHEN pa.is_correct = TRUE THEN 1 ELSE 0 END), 0) AS correct "
            + "FROM practice_answer pa "
            + "JOIN practice_session_question psq ON psq.id = pa.practice_session_question_id "
            + "JOIN practice_session ps ON ps.id = psq.practice_session_id AND ps.space_id = pa.space_id "
            + "JOIN question_knowledge_point qkp ON qkp.question_id = pa.question_id AND qkp.space_id = pa.space_id "
            + "WHERE pa.space_id = #{spaceId} AND pa.user_subject = #{userSubject} "
            + "  AND ps.status = 'SUBMITTED' "
            + "  AND pa.answered_at &gt;= #{from} AND pa.answered_at &lt; #{to} "
            + "GROUP BY qkp.knowledge_point_id"
            + "</script>")
    List<Map<String, Object>> selectKpPracticeTrend(@Param("spaceId") Long spaceId,
                                                    @Param("userSubject") String userSubject,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT DATE(pa.answered_at) AS bucket, COUNT(*) AS practice_count, "
            + "  COALESCE(SUM(CASE WHEN pa.is_correct = TRUE THEN 1 ELSE 0 END), 0) AS correct_count "
            + "FROM practice_answer pa "
            + "JOIN practice_session_question psq ON psq.id = pa.practice_session_question_id "
            + "JOIN practice_session ps ON ps.id = psq.practice_session_id AND ps.space_id = pa.space_id "
            + "WHERE pa.space_id = #{spaceId} AND pa.user_subject = #{userSubject} "
            + "  AND ps.status = 'SUBMITTED' "
            + "  AND pa.answered_at &gt;= #{from} AND pa.answered_at &lt; #{to} "
            + "GROUP BY DATE(pa.answered_at) "
            + "ORDER BY bucket ASC"
            + "</script>")
    List<Map<String, Object>> selectPracticeDailyTrend(@Param("spaceId") Long spaceId,
                                                       @Param("userSubject") String userSubject,
                                                       @Param("from") LocalDate from,
                                                       @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT DATE(eat.created_at) AS bucket, COUNT(*) AS attempt_count, "
            + "  COALESCE(AVG(er.score), 0) AS avg_score, "
            + "  COALESCE(AVG(er.max_score), 0) AS avg_max_score "
            + "FROM exam_attempt eat "
            + "LEFT JOIN exam_result er ON er.exam_attempt_id = eat.id "
            + "WHERE eat.space_id = #{spaceId} AND eat.user_subject = #{userSubject} "
            + "  AND eat.status = 'SUBMITTED' "
            + "  AND eat.created_at &gt;= #{from} AND eat.created_at &lt; #{to} "
            + "GROUP BY DATE(eat.created_at) "
            + "ORDER BY bucket ASC"
            + "</script>")
    List<Map<String, Object>> selectExamTrend(@Param("spaceId") Long spaceId,
                                              @Param("userSubject") String userSubject,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT DATE(rr.completed_at) AS bucket, COUNT(*) AS completed_count "
            + "FROM review_record rr "
            + "WHERE rr.space_id = #{spaceId} AND rr.user_subject = #{userSubject} "
            + "  AND rr.completed_at &gt;= #{from} AND rr.completed_at &lt; #{to} "
            + "GROUP BY DATE(rr.completed_at) "
            + "ORDER BY bucket ASC"
            + "</script>")
    List<Map<String, Object>> selectReviewCompletionTrend(@Param("spaceId") Long spaceId,
                                                          @Param("userSubject") String userSubject,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT DATE(st.completed_at) AS bucket, COUNT(*) AS count "
            + "FROM study_task st "
            + "WHERE st.space_id = #{spaceId} AND st.user_subject = #{userSubject} "
            + "  AND st.status = 'DONE' "
            + "  AND st.completed_at &gt;= #{from} AND st.completed_at &lt; #{to} "
            + "GROUP BY DATE(st.completed_at) "
            + "ORDER BY bucket ASC"
            + "</script>")
    List<Map<String, Object>> selectStudyTaskCompletionTrend(@Param("spaceId") Long spaceId,
                                                             @Param("userSubject") String userSubject,
                                                             @Param("from") LocalDate from,
                                                             @Param("to") LocalDate to);

    @Select("<script>"
            + "SELECT eq.question_type AS questionType, COUNT(*) AS total, "
            + "  COALESCE(SUM(CASE WHEN ea.is_correct = TRUE THEN 1 ELSE 0 END), 0) AS correct "
            + "FROM exam_answer ea "
            + "JOIN exam_question eq ON eq.id = ea.exam_question_id "
            + "JOIN exam_attempt eat ON eat.id = ea.exam_attempt_id "
            + "  AND eat.space_id = ea.space_id AND eat.user_subject = #{userSubject} "
            + "  AND eat.status = 'SUBMITTED' "
            + "JOIN learning_space ls ON ls.id = ea.space_id AND ls.owner_subject = #{userSubject} "
            + "WHERE ea.space_id = #{spaceId} AND ea.grading_status = 'GRADED' "
            + "  AND ea.is_correct IS NOT NULL "
            + "GROUP BY eq.question_type"
            + "</script>")
    List<Map<String, Object>> selectQuestionTypePerformance(@Param("spaceId") Long spaceId,
                                                            @Param("userSubject") String userSubject);

    @Select("<script>"
            + "SELECT ls.id AS spaceId, ls.name AS spaceName, "
            + "  COUNT(DISTINCT ls.owner_subject) AS memberCount, "
            + "  COUNT(DISTINCT eat.id) AS examCount, "
            + "  COUNT(DISTINCT m.id) AS masteryCount, "
            + "  COALESCE(AVG(m.mastery_score), 0) AS avgMasteryScore "
            + "FROM learning_space ls "
            + "LEFT JOIN exam_attempt eat ON eat.space_id = ls.id AND eat.status = 'SUBMITTED' "
            + "LEFT JOIN mastery m ON m.space_id = ls.id "
            + "GROUP BY ls.id, ls.name"
            + "</script>")
    List<Map<String, Object>> selectGlobalSpaceOverview();
}
