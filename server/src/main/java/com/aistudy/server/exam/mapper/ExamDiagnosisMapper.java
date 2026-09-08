package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamDiagnosis;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * BUSINESS-015 — mapper for {@link ExamDiagnosis}.
 *
 * <p>Scoped reads only; insert via {@link BaseMapper} inside the exam
 * submit transaction (exactly one row per attempt, enforced by the
 * uk_exam_diagnosis_attempt unique key).
 */
@Mapper
public interface ExamDiagnosisMapper extends BaseMapper<ExamDiagnosis> {

    /** One diagnosis of an attempt, scoped to space + user. */
    @Select("SELECT * FROM exam_diagnosis "
            + "WHERE exam_attempt_id = #{attemptId} AND space_id = #{spaceId} "
            + "  AND user_subject = #{userSubject} LIMIT 1")
    ExamDiagnosis selectByAttempt(@Param("attemptId") Long attemptId,
                                  @Param("spaceId") Long spaceId,
                                  @Param("userSubject") String userSubject);
}
