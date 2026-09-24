package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamPaper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * BUSINESS-012 — mapper for {@link ExamPaper}.
 *
 * <p>Space-scoped reads; insert via {@link BaseMapper} inside the
 * publish transaction.
 */
@Mapper
public interface ExamPaperMapper extends BaseMapper<ExamPaper> {

    /** The paper of an exam (V1: exactly one after publish). */
    @Select("SELECT * FROM exam_paper WHERE space_id = #{spaceId} AND exam_id = #{examId} "
            + "ORDER BY paper_version DESC, id DESC LIMIT 1")
    ExamPaper selectLatestByExam(@Param("spaceId") Long spaceId,
                                 @Param("examId") Long examId);

    @Select("SELECT * FROM exam_paper WHERE space_id = #{spaceId} AND exam_id = #{examId} "
            + "ORDER BY CASE status WHEN 'DRAFT' THEN 0 ELSE 1 END, paper_version DESC, id DESC LIMIT 1")
    ExamPaper selectLatestWorkingPaper(@Param("spaceId") Long spaceId,
                                       @Param("examId") Long examId);

    @Select("SELECT * FROM exam_paper WHERE space_id = #{spaceId} AND id = #{paperId} LIMIT 1")
    ExamPaper selectBySpaceId(@Param("spaceId") Long spaceId,
                              @Param("paperId") Long paperId);

    /** DRAFT → PUBLISHED paper transition (publish time). */
    @org.apache.ibatis.annotations.Update("UPDATE exam_paper "
            + "SET status = #{status}, published_at = #{publishedAt} "
            + "WHERE id = #{paperId} AND space_id = #{spaceId} AND status = #{fromStatus}")
    int updateStatusByIdAndSpace(@Param("paperId") Long paperId,
                                 @Param("spaceId") Long spaceId,
                                 @Param("fromStatus") String fromStatus,
                                 @Param("status") String status,
                                 @Param("publishedAt") java.time.LocalDateTime publishedAt);
}
