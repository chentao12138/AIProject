package com.aistudy.server.wrong.mapper;

import com.aistudy.server.wrong.entity.ReviewState;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * BUSINESS-017 — mapper for {@link ReviewState}.
 *
 * <p>One row per (user_subject, space_id, target_type, target_id).
 */
@Mapper
public interface ReviewStateMapper extends BaseMapper<ReviewState> {

    /** Finds the existing state for one review target (null if absent). */
    @Select("SELECT * FROM review_state "
            + "WHERE user_subject = #{userSubject} AND space_id = #{spaceId} "
            + "  AND target_type = #{targetType} AND target_id = #{targetId} "
            + "LIMIT 1")
    ReviewState selectByUserSpaceTarget(@Param("userSubject") String userSubject,
                                        @Param("spaceId") Long spaceId,
                                        @Param("targetType") String targetType,
                                        @Param("targetId") Long targetId);

    /** Upserts the state (INSERT on absent, UPDATE on present). */
    @Update("<script>"
            + "<if test='_index != null and _index > 0'>"
            + "UPDATE review_state SET "
            + "    ease_factor = #{easeFactor}, interval_days = #{intervalDays}, "
            + "    repetitions = #{repetitions}, policy_version = #{policyVersion}, "
            + "    next_due_at = #{nextDueAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND space_id = #{spaceId}"
            + "</if>"
            + "<if test='_index == null or _index == 0'>"
            + "INSERT INTO review_state "
            + "    (user_subject, space_id, target_type, target_id, "
            + "     ease_factor, interval_days, repetitions, policy_version, "
            + "     next_due_at, created_at, updated_at) "
            + "VALUES "
            + "    (#{userSubject}, #{spaceId}, #{targetType}, #{targetId}, "
            + "     #{easeFactor}, #{intervalDays}, #{repetitions}, #{policyVersion}, "
            + "     #{nextDueAt}, #{createdAt}, #{updatedAt})"
            + "</if>"
            + "</script>")
    int upsert(@Param("spaceId") Long spaceId,
               @Param("userSubject") String userSubject,
               @Param("targetType") String targetType,
               @Param("targetId") Long targetId,
               @Param("easeFactor") Double easeFactor,
               @Param("intervalDays") Integer intervalDays,
               @Param("repetitions") Integer repetitions,
               @Param("policyVersion") Integer policyVersion,
               @Param("nextDueAt") LocalDateTime nextDueAt,
               @Param("createdAt") LocalDateTime createdAt,
               @Param("updatedAt") LocalDateTime updatedAt);
}
