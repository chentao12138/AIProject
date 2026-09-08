package com.aistudy.server.practice.mapper;

import com.aistudy.server.practice.entity.PracticeSessionQuestion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-009 — mapper for {@link PracticeSessionQuestion}.
 *
 * <p>Space-scoped reads only; inserts via {@link BaseMapper} after the
 * service validated the session and its questions.
 */
@Mapper
public interface PracticeSessionQuestionMapper extends BaseMapper<PracticeSessionQuestion> {

    /** The fixed composition of one session, in display order. */
    @Select("SELECT * FROM practice_session_question "
            + "WHERE space_id = #{spaceId} AND practice_session_id = #{sessionId} "
            + "ORDER BY sort_order ASC, id ASC")
    List<PracticeSessionQuestion> selectBySessionId(@Param("spaceId") Long spaceId,
                                                    @Param("sessionId") Long sessionId);

    /** One slot by its id, constrained to the session (answer target). */
    @Select("SELECT * FROM practice_session_question "
            + "WHERE space_id = #{spaceId} AND practice_session_id = #{sessionId} "
            + "  AND id = #{slotId} LIMIT 1")
    PracticeSessionQuestion selectByIdAndSession(@Param("spaceId") Long spaceId,
                                                  @Param("sessionId") Long sessionId,
                                                  @Param("slotId") Long slotId);
}
