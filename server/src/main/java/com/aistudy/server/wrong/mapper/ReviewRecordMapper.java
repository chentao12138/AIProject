package com.aistudy.server.wrong.mapper;

import com.aistudy.server.wrong.entity.ReviewRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-011 — mapper for {@link ReviewRecord}.
 *
 * <p>Immutable history; inserts via {@link BaseMapper}, reads scoped.
 */
@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecord> {

    /** Completion history of ONE review task. */
    @Select("SELECT * FROM review_record "
            + "WHERE space_id = #{spaceId} AND review_task_id = #{taskId} "
            + "ORDER BY id ASC")
    List<ReviewRecord> selectByTaskId(@Param("spaceId") Long spaceId,
                                      @Param("taskId") Long taskId);

    /** Recent results for targets of a type (KNOWLEDGE_POINT reviews). */
    @Select("SELECT rr.result FROM review_record rr "
            + "JOIN review_task rt ON rt.id = rr.review_task_id "
            + "WHERE rr.space_id = #{spaceId} AND rr.user_subject = #{userSubject} "
            + "  AND rt.target_type = #{targetType} AND rt.target_id = #{targetId} "
            + "ORDER BY rr.completed_at DESC, rr.id DESC LIMIT #{limit}")
    List<String> selectRecentResults(@Param("spaceId") Long spaceId,
                                     @Param("userSubject") String userSubject,
                                     @Param("targetType") String targetType,
                                     @Param("targetId") Long targetId,
                                     @Param("limit") int limit);
}
