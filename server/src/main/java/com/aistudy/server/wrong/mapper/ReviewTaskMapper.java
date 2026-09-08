package com.aistudy.server.wrong.mapper;

import com.aistudy.server.wrong.entity.ReviewTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-011 — mapper for {@link ReviewTask}.
 *
 * <p>Space + user scoped reads; scoped updates.
 */
@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /** PENDING review task for one target (dedupe on schedule). */
    @Select("SELECT * FROM review_task "
            + "WHERE user_subject = #{userSubject} AND space_id = #{spaceId} "
            + "  AND target_type = #{targetType} AND target_id = #{targetId} "
            + "  AND status = #{status} LIMIT 1")
    ReviewTask selectPendingByTarget(@Param("userSubject") String userSubject,
                                     @Param("spaceId") Long spaceId,
                                     @Param("targetType") String targetType,
                                     @Param("targetId") Long targetId,
                                     @Param("status") String status);

    /** Open (PENDING) tasks only (due filter optional), JOIN owner scope. */
    @Select("<script>"
            + "SELECT rt.* FROM review_task rt "
            + "JOIN learning_space ls ON ls.id = rt.space_id "
            + "WHERE rt.space_id = #{spaceId} "
            + "  AND rt.user_subject = #{userSubject} "
            + "  AND rt.status = 'PENDING' "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "<if test='dueBefore != null'> AND rt.due_at &lt;= #{dueBefore} </if>"
            + "ORDER BY rt.due_at ASC, rt.id ASC"
            + "</script>")
    List<ReviewTask> selectBySpaceOwnerUser(@Param("spaceId") Long spaceId,
                                            @Param("userSubject") String userSubject,
                                            @Param("ownerSubject") String ownerSubject,
                                            @Param("dueBefore") LocalDateTime dueBefore);

    /** PENDING tasks only (StudyPlan generation input), due first. */
    @Select("SELECT rt.* FROM review_task rt "
            + "JOIN learning_space ls ON ls.id = rt.space_id "
            + "WHERE rt.space_id = #{spaceId} "
            + "  AND rt.user_subject = #{userSubject} "
            + "  AND rt.status = 'PENDING' "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY rt.due_at ASC, rt.id ASC")
    List<ReviewTask> selectPendingBySpaceOwnerUser(@Param("spaceId") Long spaceId,
                                                   @Param("userSubject") String userSubject,
                                                   @Param("ownerSubject") String ownerSubject);

    /** One task scoped to user+space+owner. */
    @Select("SELECT rt.* FROM review_task rt "
            + "JOIN learning_space ls ON ls.id = rt.space_id "
            + "WHERE rt.id = #{taskId} "
            + "  AND rt.space_id = #{spaceId} "
            + "  AND rt.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    ReviewTask selectByIdSpaceOwnerUser(@Param("taskId") Long taskId,
                                        @Param("spaceId") Long spaceId,
                                        @Param("userSubject") String userSubject,
                                        @Param("ownerSubject") String ownerSubject);

    /** PENDING → COMPLETED guarded transition. */
    @Update("UPDATE review_task "
            + "SET status = #{toStatus}, updated_at = #{updatedAt} "
            + "WHERE id = #{taskId} AND space_id = #{spaceId} "
            + "  AND user_subject = #{userSubject} AND status = #{fromStatus}")
    int completeByIdAndSpace(@Param("taskId") Long taskId,
                             @Param("spaceId") Long spaceId,
                             @Param("userSubject") String userSubject,
                             @Param("fromStatus") String fromStatus,
                             @Param("toStatus") String toStatus,
                             @Param("updatedAt") LocalDateTime updatedAt);

    /** Reschedule an existing PENDING task (due refresh). */
    @Update("UPDATE review_task "
            + "SET due_at = #{dueAt}, priority = #{priority}, reason = #{reason}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{taskId} AND space_id = #{spaceId} AND user_subject = #{userSubject}")
    int rescheduleByIdAndSpace(@Param("taskId") Long taskId,
                               @Param("spaceId") Long spaceId,
                               @Param("userSubject") String userSubject,
                               @Param("dueAt") LocalDateTime dueAt,
                               @Param("priority") String priority,
                               @Param("reason") String reason,
                               @Param("updatedAt") LocalDateTime updatedAt);
}
