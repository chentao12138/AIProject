package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamDiagnosisItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-015 — mapper for {@link ExamDiagnosisItem}.
 *
 * <p>Insert via {@link BaseMapper} during diagnosis generation;
 * reads are always anchored on the diagnosis id (which itself is
 * owner-scoped via {@link ExamDiagnosisMapper#selectByAttempt}).
 */
@Mapper
public interface ExamDiagnosisItemMapper extends BaseMapper<ExamDiagnosisItem> {

    @Select("SELECT * FROM exam_diagnosis_item "
            + "WHERE exam_diagnosis_id = #{diagnosisId} ORDER BY id ASC")
    List<ExamDiagnosisItem> selectByDiagnosisId(@Param("diagnosisId") Long diagnosisId);

    /**
     * KNOWLEDGE_POINT items of the user's LATEST diagnosis in the
     * space (StudyPlan reason enrichment, BUSINESS-016).
     */
    @Select("SELECT edi.* FROM exam_diagnosis_item edi "
            + "JOIN exam_diagnosis ed ON ed.id = edi.exam_diagnosis_id "
            + "WHERE ed.user_subject = #{userSubject} AND ed.space_id = #{spaceId} "
            + "  AND edi.dimension_type = 'KNOWLEDGE_POINT' "
            + "  AND ed.created_at = (SELECT MAX(ed2.created_at) FROM exam_diagnosis ed2 "
            + "                       WHERE ed2.user_subject = ed.user_subject "
            + "                         AND ed2.space_id = ed.space_id) "
            + "ORDER BY edi.dimension_id ASC")
    List<ExamDiagnosisItem> selectLatestKpItemsByUserSpace(
            @Param("userSubject") String userSubject,
            @Param("spaceId") Long spaceId);
}
