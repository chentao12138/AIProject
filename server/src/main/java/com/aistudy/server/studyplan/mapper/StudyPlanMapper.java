package com.aistudy.server.studyplan.mapper;

import com.aistudy.server.studyplan.entity.StudyPlan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * BUSINESS-016 — mapper for {@link StudyPlan}.
 *
 * <p>Scoped reads (user + space + owner JOIN); insert via
 * {@link BaseMapper} inside the generate transaction.
 */
@Mapper
public interface StudyPlanMapper extends BaseMapper<StudyPlan> {

    /** The ACTIVE plan of (user, space), if any (409 conflict check). */
    @Select("SELECT sp.* FROM study_plan sp "
            + "JOIN learning_space ls ON ls.id = sp.space_id "
            + "WHERE sp.user_subject = #{userSubject} AND sp.space_id = #{spaceId} "
            + "  AND sp.status = #{status} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sp.created_at DESC, sp.id DESC LIMIT 1")
    StudyPlan selectByUserSpaceStatus(@Param("userSubject") String userSubject,
                                      @Param("spaceId") Long spaceId,
                                      @Param("status") String status,
                                      @Param("ownerSubject") String ownerSubject);

    /** Latest plan (any status) of (user, space) — the singular "current plan". */
    @Select("SELECT sp.* FROM study_plan sp "
            + "JOIN learning_space ls ON ls.id = sp.space_id "
            + "WHERE sp.user_subject = #{userSubject} AND sp.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sp.created_at DESC, sp.id DESC LIMIT 1")
    StudyPlan selectLatestByUserSpace(@Param("userSubject") String userSubject,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);

    /** ACTIVE → COMPLETED guarded transition (all tasks done). */
    @Update("UPDATE study_plan "
            + "SET status = #{toStatus}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = #{fromStatus}")
    int updateStatusById(@Param("id") Long id,
                         @Param("fromStatus") String fromStatus,
                         @Param("toStatus") String toStatus,
                         @Param("updatedAt") LocalDateTime updatedAt);
}
