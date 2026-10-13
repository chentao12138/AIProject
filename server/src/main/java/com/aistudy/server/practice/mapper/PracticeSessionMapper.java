package com.aistudy.server.practice.mapper;

import com.aistudy.server.practice.entity.PracticeSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-009 — production mapper for {@link PracticeSession}.
 *
 * <p>All reads scope on {@code user_subject + space_id + owner
 * (JOIN learning_space)}. BaseMapper is used only for insert.
 */
@Mapper
public interface PracticeSessionMapper extends BaseMapper<PracticeSession> {

    @Select("SELECT ps.* FROM practice_session ps "
            + "JOIN learning_space ls ON ls.id = ps.space_id "
            + "WHERE ps.id = #{sessionId} "
            + "  AND ps.space_id = #{spaceId} "
            + "  AND ps.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    PracticeSession selectByIdSpaceOwnerUser(@Param("sessionId") Long sessionId,
                                             @Param("spaceId") Long spaceId,
                                             @Param("userSubject") String userSubject,
                                             @Param("ownerSubject") String ownerSubject);

    @Select("SELECT ps.* FROM practice_session ps "
            + "JOIN learning_space ls ON ls.id = ps.space_id "
            + "WHERE ps.space_id = #{spaceId} "
            + "  AND ps.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY ps.created_at DESC, ps.id DESC")
    List<PracticeSession> selectBySpaceOwnerUser(@Param("spaceId") Long spaceId,
                                                 @Param("userSubject") String userSubject,
                                                 @Param("ownerSubject") String ownerSubject);

    /** Same as above but with optional time-range filters on created_at. */
    @Select("<script>"
            + "SELECT ps.* FROM practice_session ps "
            + "JOIN learning_space ls ON ls.id = ps.space_id "
            + "WHERE ps.space_id = #{spaceId} "
            + "  AND ps.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "<if test='fromTime != null'> AND ps.created_at &gt;= #{fromTime} </if>"
            + "<if test='toTime != null'> AND ps.created_at &lt;= #{toTime} </if>"
            + "ORDER BY ps.created_at DESC, ps.id DESC"
            + "</script>")
    List<PracticeSession> selectBySpaceOwnerUser(@Param("spaceId") Long spaceId,
                                                 @Param("userSubject") String userSubject,
                                                 @Param("ownerSubject") String ownerSubject,
                                                 @Param("fromTime") LocalDateTime fromTime,
                                                 @Param("toTime") LocalDateTime toTime);

    /**
     * State transition guarded in SQL: only rows currently in
     * {@code fromStatus} flip to {@code toStatus}. Returns 0 when the
     * row is not there or already moved — caller maps to 409/404.
     */
    @Update("UPDATE practice_session "
            + "SET status = #{toStatus}, "
            + "    started_at = #{startedAt}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{sessionId} "
            + "  AND space_id = #{spaceId} "
            + "  AND user_subject = #{userSubject} "
            + "  AND status = #{fromStatus}")
    int updateStatusByIdAndSpace(@Param("sessionId") Long sessionId,
                                 @Param("spaceId") Long spaceId,
                                 @Param("userSubject") String userSubject,
                                 @Param("fromStatus") String fromStatus,
                                 @Param("toStatus") String toStatus,
                                 @Param("startedAt") LocalDateTime startedAt,
                                 @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE practice_session "
            + "SET status = #{toStatus}, "
            + "    finished_at = #{finishedAt}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{sessionId} "
            + "  AND space_id = #{spaceId} "
            + "  AND user_subject = #{userSubject} "
            + "  AND status = #{fromStatus}")
    int finishByIdAndSpace(@Param("sessionId") Long sessionId,
                           @Param("spaceId") Long spaceId,
                           @Param("userSubject") String userSubject,
                           @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus,
                           @Param("finishedAt") LocalDateTime finishedAt,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Stores the summary of a submission on the session row (V066) so a
     * finished session can be read back later without re-deriving the score.
     *
     * <p>Conditioned on {@code status = 'SUBMITTED'} and on the owner, so this
     * can only ever annotate a session the caller just transitioned; it cannot
     * write a result onto an in-progress or foreign session.
     */
    @Update("UPDATE practice_session "
            + "SET score = #{score}, max_score = #{maxScore}, "
            + "    answered_count = #{answeredCount}, correct_count = #{correctCount}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{sessionId} AND space_id = #{spaceId} "
            + "  AND user_subject = #{userSubject} AND status = 'SUBMITTED'")
    int storeResultByIdAndSpace(@Param("sessionId") Long sessionId,
                                @Param("spaceId") Long spaceId,
                                @Param("userSubject") String userSubject,
                                @Param("score") int score,
                                @Param("maxScore") int maxScore,
                                @Param("answeredCount") int answeredCount,
                                @Param("correctCount") int correctCount,
                                @Param("updatedAt") LocalDateTime updatedAt);
}
