package com.aistudy.server.ai.mapper;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AIGenerationJobMapper extends BaseMapper<AIGenerationJob> {

    @Select("SELECT * FROM ai_generation_job "
            + "WHERE id = #{jobId} AND requester = #{requester} LIMIT 1")
    AIGenerationJob selectByIdAndRequester(@Param("jobId") Long jobId,
                                            @Param("requester") String requester);

    @Select("SELECT * FROM ai_generation_job "
            + "WHERE requester = #{requester} "
            + "ORDER BY created_at DESC, id DESC "
            + "LIMIT #{limit} OFFSET #{offset}")
    List<AIGenerationJob> selectByRequester(@Param("requester") String requester,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_generation_job WHERE requester = #{requester}")
    long countByRequester(String requester);

    @Update("UPDATE ai_generation_job "
            + "SET status = #{status}, progress = #{progress}, error_code = #{errorCode}, "
            + " safe_message = #{safeMessage}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatus(@Param("id") Long id,
                     @Param("expectedStatus") String expectedStatus,
                     @Param("status") String status,
                     @Param("progress") Integer progress,
                     @Param("errorCode") String errorCode,
                     @Param("safeMessage") String safeMessage,
                     @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE ai_generation_job "
            + "SET status = #{status}, progress = #{progress}, "
            + " success_count = #{successCount}, failure_count = #{failureCount}, "
            + " finished_at = #{finishedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int finishJob(@Param("id") Long id,
                  @Param("expectedStatus") String expectedStatus,
                  @Param("status") String status,
                  @Param("progress") Integer progress,
                  @Param("successCount") Integer successCount,
                  @Param("failureCount") Integer failureCount,
                  @Param("finishedAt") LocalDateTime finishedAt,
                  @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE ai_generation_job "
            + "SET status = #{status}, progress = #{progress}, retry_count = retry_count + 1, "
            + " error_code = NULL, safe_message = NULL, started_at = NULL, finished_at = NULL, "
            + " updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = 'FAILED'")
    int retryFailed(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT * FROM ai_generation_job "
            + "WHERE (#{status} IS NULL OR status = #{status}) "
            + "  AND (#{spaceId} IS NULL OR space_id = #{spaceId}) "
            + "ORDER BY created_at DESC, id DESC")
    List<AIGenerationJob> selectAllAdmin(@Param("status") String status,
                                         @Param("spaceId") Long spaceId);

    /** Atomic claim: only one worker moves PENDING → PROCESSING. */
    @Update("UPDATE ai_generation_job "
            + "SET status = 'PROCESSING', progress = 1, "
            + " claimed_by = #{workerId}, claimed_at = #{now}, started_at = #{now}, "
            + " updated_at = #{now} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int claimPending(@Param("id") Long id,
                     @Param("workerId") String workerId,
                     @Param("now") LocalDateTime now);

    @Select("SELECT * FROM ai_generation_job WHERE status = 'PENDING' "
            + "ORDER BY created_at ASC, id ASC LIMIT #{limit}")
    List<AIGenerationJob> selectPendingBatch(@Param("limit") int limit);

    @Update("UPDATE ai_generation_job "
            + "SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL, updated_at = #{now} "
            + "WHERE status = 'PROCESSING' AND (claimed_at IS NULL OR claimed_at < #{staleBefore})")
    int requeueStaleProcessing(@Param("now") LocalDateTime now,
                               @Param("staleBefore") LocalDateTime staleBefore);
}
