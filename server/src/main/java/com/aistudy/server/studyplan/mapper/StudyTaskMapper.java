package com.aistudy.server.studyplan.mapper;

import com.aistudy.server.studyplan.entity.StudyTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-016 — mapper for {@link StudyTask}.
 *
 * <p>Scoped reads; guarded status transition (TODO/IN_PROGRESS →
 * DONE); completion of an already-DONE task is idempotent (the
 * service re-reads before updating).
 */
@Mapper
public interface StudyTaskMapper extends BaseMapper<StudyTask> {

    /** All tasks of one plan (display order). */
    @Select("SELECT * FROM study_task "
            + "WHERE study_plan_id = #{planId} ORDER BY id ASC")
    List<StudyTask> selectByPlanId(@Param("planId") Long planId);

    /** One task scoped to user + space + owner. */
    @Select("SELECT st.* FROM study_task st "
            + "JOIN learning_space ls ON ls.id = st.space_id "
            + "WHERE st.id = #{taskId} AND st.space_id = #{spaceId} "
            + "  AND st.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    StudyTask selectByIdSpaceOwnerUser(@Param("taskId") Long taskId,
                                       @Param("spaceId") Long spaceId,
                                       @Param("userSubject") String userSubject,
                                       @Param("ownerSubject") String ownerSubject);

    /** TODO/IN_PROGRESS → DONE guarded transition (0 rows = concurrent change). */
    @Update("UPDATE study_task "
            + "SET status = #{toStatus}, completed_at = #{completedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{taskId} AND space_id = #{spaceId} "
            + "  AND user_subject = #{userSubject} "
            + "  AND status IN (#{fromStatus}, #{fromStatus2})")
    int completeByIdAndSpace(@Param("taskId") Long taskId,
                             @Param("spaceId") Long spaceId,
                             @Param("userSubject") String userSubject,
                             @Param("fromStatus") String fromStatus,
                             @Param("fromStatus2") String fromStatus2,
                             @Param("toStatus") String toStatus,
                             @Param("completedAt") LocalDateTime completedAt,
                             @Param("updatedAt") LocalDateTime updatedAt);

    /** Open (not DONE/SKIPPED) task count of a plan — drives plan completion. */
    @Select("SELECT COUNT(*) FROM study_task "
            + "WHERE study_plan_id = #{planId} AND status NOT IN ('DONE', 'SKIPPED')")
    int countOpenByPlanId(@Param("planId") Long planId);
}
