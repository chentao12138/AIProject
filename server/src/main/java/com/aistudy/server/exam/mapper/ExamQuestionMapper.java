package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamQuestion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /** One slot by id constrained to space (grading path). */
    @Select("SELECT * FROM exam_question WHERE space_id = #{spaceId} AND id = #{examQuestionId} LIMIT 1")
    ExamQuestion selectByIdAndSpace(@Param("spaceId") Long spaceId,
                                    @Param("examQuestionId") Long examQuestionId);

    /** One slot by id constrained to the paper (answer target). */
    @Select("SELECT * FROM exam_question "
            + "WHERE space_id = #{spaceId} AND exam_paper_id = #{paperId} "
            + "  AND id = #{examQuestionId} LIMIT 1")
    ExamQuestion selectByIdAndPaper(@Param("spaceId") Long spaceId,
                                    @Param("paperId") Long paperId,
                                    @Param("examQuestionId") Long examQuestionId);

    /** Delete one slot from a draft paper (composition edit). */
    @Update("DELETE FROM exam_question "
            + "WHERE id = #{examQuestionId} AND exam_paper_id = #{paperId} AND space_id = #{spaceId}")
    int deleteByIdAndPaper(@Param("examQuestionId") Long examQuestionId,
                           @Param("paperId") Long paperId,
                           @Param("spaceId") Long spaceId);

    /** Batch reorder: set sort_order for multiple slots of one paper. */
    @Update("<script>"
            + "<foreach collection='slots' item='slot' separator=';'>"
            + "UPDATE exam_question SET sort_order = #{slot.sortOrder}, updated_at = NOW(6) "
            + "WHERE id = #{slot.id} AND exam_paper_id = #{paperId} AND space_id = #{spaceId}"
            + "</foreach>"
            + "</script>")
    int updateSortOrderBatch(@Param("paperId") Long paperId,
                             @Param("spaceId") Long spaceId,
                             @Param("slots") List<SortSlot> slots);

    /** Update per-question score on a draft paper slot. */
    @Update("UPDATE exam_question SET score = #{score}, updated_at = NOW(6) "
            + "WHERE id = #{examQuestionId} AND exam_paper_id = #{paperId} AND space_id = #{spaceId}")
    int updateScoreByIdAndPaper(@Param("examQuestionId") Long examQuestionId,
                                @Param("paperId") Long paperId,
                                @Param("spaceId") Long spaceId,
                                @Param("score") Integer score);

    /** Owner-scoped single slot for post-submit AI explanation (AI-007). */
    @Select("SELECT eq.* FROM exam_question eq "
            + "JOIN learning_space ls ON ls.id = eq.space_id "
            + "WHERE eq.id = #{examQuestionId} AND eq.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} LIMIT 1")
    ExamQuestion selectByIdSpaceOwner(@Param("examQuestionId") Long examQuestionId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);

    public record SortSlot(Long id, Integer sortOrder) {
    }
}
