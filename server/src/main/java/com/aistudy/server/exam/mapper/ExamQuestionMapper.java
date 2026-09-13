package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamQuestion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-012 — mapper for {@link ExamQuestion}.
 *
 * <p>Space-scoped reads; insert via {@link BaseMapper} at publish.
 */
@Mapper
public interface ExamQuestionMapper extends BaseMapper<ExamQuestion> {

    /** The fixed composition of a paper, in display order. */
    @Select("SELECT * FROM exam_question "
            + "WHERE space_id = #{spaceId} AND exam_paper_id = #{paperId} "
            + "ORDER BY sort_order ASC, id ASC")
    List<ExamQuestion> selectByPaperId(@Param("spaceId") Long spaceId,
                                       @Param("paperId") Long paperId);

    /** One slot by id constrained to the paper (answer target). */
    @Select("SELECT * FROM exam_question "
            + "WHERE space_id = #{spaceId} AND exam_paper_id = #{paperId} "
            + "  AND id = #{examQuestionId} LIMIT 1")
    ExamQuestion selectByIdAndPaper(@Param("spaceId") Long spaceId,
                                    @Param("paperId") Long paperId,
                                    @Param("examQuestionId") Long examQuestionId);

    /** Owner-scoped single slot for post-submit AI explanation (AI-007). */
    @Select("SELECT eq.* FROM exam_question eq "
            + "JOIN learning_space ls ON ls.id = eq.space_id "
            + "WHERE eq.id = #{examQuestionId} AND eq.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} LIMIT 1")
    ExamQuestion selectByIdSpaceOwner(@Param("examQuestionId") Long examQuestionId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);
}
