package com.aistudy.server.wrong.mapper;

import com.aistudy.server.wrong.entity.WrongQuestion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-011 — mapper for {@link WrongQuestion}.
 *
 * <p>Space + user scoped reads; scoped upsert increments.
 */
@Mapper
public interface WrongQuestionMapper extends BaseMapper<WrongQuestion> {

    /** One aggregate row per (user, space, question). */
    @Select("SELECT * FROM wrong_question "
            + "WHERE user_subject = #{userSubject} AND space_id = #{spaceId} "
            + "  AND question_id = #{questionId} LIMIT 1")
    WrongQuestion selectByUserSpaceQuestion(@Param("userSubject") String userSubject,
                                            @Param("spaceId") Long spaceId,
                                            @Param("questionId") Long questionId);

    /** Wrong answers of one space (JOIN owner), newest wrong first. */
    @Select("SELECT wq.* FROM wrong_question wq "
            + "JOIN learning_space ls ON ls.id = wq.space_id "
            + "WHERE wq.space_id = #{spaceId} "
            + "  AND wq.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY wq.last_wrong_at DESC, wq.id DESC")
    List<WrongQuestion> selectBySpaceOwnerUser(@Param("spaceId") Long spaceId,
                                               @Param("userSubject") String userSubject,
                                               @Param("ownerSubject") String ownerSubject);

    /** Register another wrong answer (scoped increment). */
    @Update("UPDATE wrong_question "
            + "SET wrong_count = wrong_count + 1, "
            + "    last_wrong_at = #{lastWrongAt}, "
            + "    status = #{status}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND space_id = #{spaceId} AND user_subject = #{userSubject}")
    int incrementWrong(@Param("id") Long id,
                       @Param("spaceId") Long spaceId,
                       @Param("userSubject") String userSubject,
                       @Param("lastWrongAt") LocalDateTime lastWrongAt,
                       @Param("status") String status,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** Record a correct review result + status transition. */
    @Update("UPDATE wrong_question "
            + "SET last_correct_at = #{lastCorrectAt}, "
            + "    status = #{status}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND space_id = #{spaceId} AND user_subject = #{userSubject}")
    int markCorrect(@Param("id") Long id,
                    @Param("spaceId") Long spaceId,
                    @Param("userSubject") String userSubject,
                    @Param("lastCorrectAt") LocalDateTime lastCorrectAt,
                    @Param("status") String status,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /** Recent completion history of ONE question (via review_task). */
    @Select("SELECT rr.result FROM review_record rr "
            + "JOIN review_task rt ON rt.id = rr.review_task_id "
            + "WHERE rr.space_id = #{spaceId} "
            + "  AND rr.user_subject = #{userSubject} "
            + "  AND rt.target_type = 'QUESTION' "
            + "  AND rt.target_id = #{questionId} "
            + "ORDER BY rr.completed_at DESC, rr.id DESC "
            + "LIMIT #{limit}")
    List<String> selectRecentReviewResults(@Param("spaceId") Long spaceId,
                                           @Param("userSubject") String userSubject,
                                           @Param("questionId") Long questionId,
                                           @Param("limit") int limit);
}
