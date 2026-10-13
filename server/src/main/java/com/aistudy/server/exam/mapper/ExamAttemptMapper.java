package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-013 — mapper for {@link ExamAttempt}.
 *
 * <p>All reads scoped on user_subject + space_id + owner (JOIN).
 */
@Mapper
public interface ExamAttemptMapper extends BaseMapper<ExamAttempt> {

    @Select("SELECT ea.* FROM exam_attempt ea "
            + "JOIN learning_space ls ON ls.id = ea.space_id "
            + "WHERE ea.id = #{attemptId} AND ea.space_id = #{spaceId} "
            + "  AND ea.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    ExamAttempt selectByIdSpaceOwnerUser(@Param("attemptId") Long attemptId,
                                         @Param("spaceId") Long spaceId,
                                         @Param("userSubject") String userSubject,
                                         @Param("ownerSubject") String ownerSubject);

    @Select("SELECT ea.* FROM exam_attempt ea "
            + "JOIN learning_space ls ON ls.id = ea.space_id "
            + "WHERE ea.space_id = #{spaceId} "
            + "  AND ea.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY ea.created_at DESC, ea.id DESC")
    List<ExamAttempt> selectBySpaceOwnerUser(@Param("spaceId") Long spaceId,
                                             @Param("userSubject") String userSubject,
                                             @Param("ownerSubject") String ownerSubject);

    /**
     * Paged attempt history of one caller (C11), optionally narrowed to a
     * single exam or status. Same owner JOIN as
     * {@link #selectBySpaceOwnerUser}, so a caller can never list another
     * user's attempts by guessing an exam id.
     */
    @Select("<script>"
            + "SELECT ea.* FROM exam_attempt ea "
            + "JOIN learning_space ls ON ls.id = ea.space_id "
            + "WHERE ea.space_id = #{spaceId} "
            + "  AND ea.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "<if test='examId != null'> AND ea.exam_id = #{examId} </if>"
            + "<if test='status != null'> AND ea.status = #{status} </if>"
            + "ORDER BY ea.created_at DESC, ea.id DESC "
            + "LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<ExamAttempt> selectPageByOwnerUser(@Param("spaceId") Long spaceId,
                                            @Param("userSubject") String userSubject,
                                            @Param("ownerSubject") String ownerSubject,
                                            @Param("examId") Long examId,
                                            @Param("status") String status,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);

    /** Total behind {@link #selectPageByOwnerUser} — identical filter block. */
    @Select("<script>"
            + "SELECT COUNT(*) FROM exam_attempt ea "
            + "JOIN learning_space ls ON ls.id = ea.space_id "
            + "WHERE ea.space_id = #{spaceId} "
            + "  AND ea.user_subject = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "<if test='examId != null'> AND ea.exam_id = #{examId} </if>"
            + "<if test='status != null'> AND ea.status = #{status} </if>"
            + "</script>")
    long countByOwnerUser(@Param("spaceId") Long spaceId,
                          @Param("userSubject") String userSubject,
                          @Param("ownerSubject") String ownerSubject,
                          @Param("examId") Long examId,
                          @Param("status") String status);

    /** NOT_STARTED → IN_PROGRESS guarded transition. */
    @Update("UPDATE exam_attempt SET status = #{toStatus}, started_at = #{startedAt}, "
            + "deadline_at = #{deadlineAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{attemptId} AND space_id = #{spaceId} "
            + "  AND user_subject = #{userSubject} AND status = #{fromStatus}")
    int startByIdAndSpace(@Param("attemptId") Long attemptId,
                          @Param("spaceId") Long spaceId,
                          @Param("userSubject") String userSubject,
                          @Param("fromStatus") String fromStatus,
                          @Param("toStatus") String toStatus,
                          @Param("startedAt") LocalDateTime startedAt,
                          @Param("deadlineAt") LocalDateTime deadlineAt,
                          @Param("updatedAt") LocalDateTime updatedAt);

    /** IN_PROGRESS → SUBMITTED guarded transition. */
    @Update("UPDATE exam_attempt SET status = #{toStatus}, submitted_at = #{submittedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{attemptId} AND space_id = #{spaceId} "
            + "  AND user_subject = #{userSubject} AND status = #{fromStatus}")
    int submitByIdAndSpace(@Param("attemptId") Long attemptId,
                           @Param("spaceId") Long spaceId,
                           @Param("userSubject") String userSubject,
                           @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus,
                           @Param("submittedAt") LocalDateTime submittedAt,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /** Expired IN_PROGRESS attempts for the auto-submit sweeper. */
    @Select("SELECT * FROM exam_attempt "
            + "WHERE status = 'IN_PROGRESS' "
            + "  AND deadline_at IS NOT NULL AND deadline_at < #{now}")
    List<ExamAttempt> selectExpiredInProgress(@Param("now") LocalDateTime now);

    @Select("SELECT ea.* FROM exam_attempt ea "
            + "WHERE ea.exam_id = #{examId} "
            + "ORDER BY ea.created_at DESC, ea.id DESC")
    List<ExamAttempt> selectByExamId(@Param("examId") Long examId);

    @Update("UPDATE exam_attempt SET grading_status = #{gradingStatus}, updated_at = #{updatedAt} "
            + "WHERE id = #{attemptId} AND space_id = #{spaceId}")
    int updateGradingStatusByIdAndSpace(@Param("attemptId") Long attemptId,
                                        @Param("spaceId") Long spaceId,
                                        @Param("gradingStatus") String gradingStatus,
                                        @Param("updatedAt") LocalDateTime updatedAt);
}
