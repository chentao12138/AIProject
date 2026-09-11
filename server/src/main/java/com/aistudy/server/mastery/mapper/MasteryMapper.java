package com.aistudy.server.mastery.mapper;

import com.aistudy.server.mastery.entity.Mastery;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * BUSINESS-014 — mapper for {@link Mastery} + evidence queries.
 *
 * <p>Evidence queries count graded objective answers of questions
 * linked to a knowledge point (CURRENT links, documented V1
 * limitation) and KNOWLEDGE_POINT-targeted review records.
 *
 * <p>Evidence STATUS contract (AUTORUN-CONTINUE-014_016 §3): practice
 * evidence only counts answers whose {@code practice_session} is
 * SUBMITTED, exam evidence only counts answers whose
 * {@code exam_attempt} is SUBMITTED — an IN_PROGRESS session/attempt
 * can never leak into a mastery recompute triggered by a later event.
 */
@Mapper
public interface MasteryMapper extends BaseMapper<Mastery> {

    @Select("SELECT * FROM mastery "
            + "WHERE user_subject = #{userSubject} AND space_id = #{spaceId} "
            + "  AND knowledge_point_id = #{kpId} LIMIT 1")
    Mastery selectByUserSpaceKp(@Param("userSubject") String userSubject,
                                @Param("spaceId") Long spaceId,
                                @Param("kpId") Long kpId);

    /**
     * Owner-scoped detail read for PUBLIC API use: joins
     * learning_space.owner_subject + same-space knowledge_point so an
     * authenticated subject can only see mastery of their own space
     * (404 anti-probing preserved by the caller).
     */
    @Select("SELECT m.* FROM mastery m "
            + "JOIN learning_space ls ON ls.id = m.space_id "
            + "JOIN knowledge_point kp ON kp.id = m.knowledge_point_id "
            + "  AND kp.space_id = m.space_id "
            + "WHERE m.user_subject = #{userSubject} AND m.space_id = #{spaceId} "
            + "  AND m.knowledge_point_id = #{kpId} "
            + "  AND ls.owner_subject = #{ownerSubject} LIMIT 1")
    Mastery selectByUserSpaceKpOwnerScoped(@Param("userSubject") String userSubject,
                                           @Param("spaceId") Long spaceId,
                                           @Param("kpId") Long kpId,
                                           @Param("ownerSubject") String ownerSubject);

    @Select("SELECT m.* FROM mastery m "
            + "JOIN learning_space ls ON ls.id = m.space_id "
            + "WHERE m.space_id = #{spaceId} AND m.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY m.mastery_score ASC, m.knowledge_point_id ASC")
    List<Mastery> selectBySpaceOwnerUser(@Param("spaceId") Long spaceId,
                                         @Param("userSubject") String userSubject,
                                         @Param("ownerSubject") String ownerSubject);

    @Update("UPDATE mastery SET mastery_score = #{score}, confidence = #{confidence}, "
            + "practice_evidence_count = #{practiceCount}, exam_evidence_count = #{examCount}, "
            + "review_evidence_count = #{reviewCount}, last_evidence_at = #{lastEvidenceAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND space_id = #{spaceId} AND user_subject = #{userSubject}")
    int updateByIdAndSpace(@Param("id") Long id,
                           @Param("spaceId") Long spaceId,
                           @Param("userSubject") String userSubject,
                           @Param("score") Double score,
                           @Param("confidence") Double confidence,
                           @Param("practiceCount") int practiceCount,
                           @Param("examCount") int examCount,
                           @Param("reviewCount") int reviewCount,
                           @Param("lastEvidenceAt") LocalDateTime lastEvidenceAt,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Practice evidence: graded count, correct count, latest
     * timestamp — SUBMITTED sessions ONLY (the answer's session is
     * joined through practice_session_question; an IN_PROGRESS
     * session can never contribute).
     */
    @Select("SELECT COUNT(*) AS cnt, "
            + "COALESCE(SUM(CASE WHEN pa.is_correct = TRUE THEN 1 ELSE 0 END), 0) AS correct, "
            + "MAX(pa.updated_at) AS last_at "
            + "FROM practice_answer pa "
            + "JOIN practice_session_question psq ON psq.id = pa.practice_session_question_id "
            + "JOIN practice_session ps ON ps.id = psq.practice_session_id "
            + "  AND ps.space_id = pa.space_id "
            + "JOIN question_knowledge_point qkp ON qkp.question_id = pa.question_id "
            + "WHERE pa.space_id = #{spaceId} AND pa.user_subject = #{userSubject} "
            + "  AND ps.status = 'SUBMITTED' "
            + "  AND qkp.knowledge_point_id = #{kpId} AND pa.is_correct IS NOT NULL")
    Map<String, Object> selectPracticeEvidence(@Param("spaceId") Long spaceId,
                                               @Param("userSubject") String userSubject,
                                               @Param("kpId") Long kpId);

    /**
     * Exam evidence: graded objective answers via paper slots +
     * attempt owner — SUBMITTED attempts ONLY.
     */
    @Select("SELECT COUNT(*) AS cnt, "
            + "COALESCE(SUM(CASE WHEN ea.is_correct = TRUE THEN 1 ELSE 0 END), 0) AS correct, "
            + "MAX(ea.answered_at) AS last_at "
            + "FROM exam_answer ea "
            + "JOIN exam_attempt eat ON eat.id = ea.exam_attempt_id "
            + "JOIN exam_question eq ON eq.id = ea.exam_question_id "
            + "JOIN question_knowledge_point qkp ON qkp.question_id = eq.question_id "
            + "WHERE ea.space_id = #{spaceId} AND eat.user_subject = #{userSubject} "
            + "  AND eat.status = 'SUBMITTED' "
            + "  AND qkp.knowledge_point_id = #{kpId} "
            + "  AND ea.grading_status = 'GRADED' AND ea.is_correct IS NOT NULL")
    Map<String, Object> selectExamEvidence(@Param("spaceId") Long spaceId,
                                           @Param("userSubject") String userSubject,
                                           @Param("kpId") Long kpId);

    /**
     * Review evidence: KNOWLEDGE_POINT-targeted review records —
     * count + latest completion timestamp (participates in
     * lastEvidenceAt).
     */
    @Select("SELECT COUNT(*) AS cnt, MAX(rr.completed_at) AS last_at "
            + "FROM review_record rr "
            + "JOIN review_task rt ON rt.id = rr.review_task_id "
            + "WHERE rr.space_id = #{spaceId} AND rr.user_subject = #{userSubject} "
            + "  AND rt.target_type = 'KNOWLEDGE_POINT' AND rt.target_id = #{kpId}")
    Map<String, Object> selectReviewEvidence(@Param("spaceId") Long spaceId,
                                             @Param("userSubject") String userSubject,
                                             @Param("kpId") Long kpId);
}
