package com.aistudy.server.ingestion.job.mapper;

import com.aistudy.server.ingestion.job.entity.IngestionJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * BUSINESS-005 — production MyBatis-Plus mapper for {@link IngestionJob}.
 *
 * <p>Extends {@link BaseMapper} for insert only. All READS are
 * explicit owner-scoped {@code @Select} queries that JOIN through
 * {@code source} AND {@code learning_space} so the owner subject and
 * the source↔space consistency are enforced in SQL (BUSINESS-004
 * pattern):
 *
 * <ul>
 *   <li>{@link #selectByIdSpaceOwner} — detail read for the
 *       space-scoped job endpoint: {@code WHERE ij.id = ? AND
 *       ij.space_id = ? AND ls.owner_subject = ?}. The JOIN also
 *       requires {@code source.space_id == ingestion_job.space_id},
 *       so a job whose source lives in ANOTHER space cannot be
 *       reached even with a correct owner + space path.</li>
 *   <li>{@link #selectBySpaceSourceOwner} — job history list for one
 *       owned source, newest first, same owner + same-space
 *       predicates.</li>
 * </ul>
 *
 * <p>Inherited unscoped BaseMapper reads are NEVER used by the
 * service layer.
 */
@Mapper
public interface IngestionJobMapper extends BaseMapper<IngestionJob> {

    /**
     * Returns the job iff ALL of: it exists, belongs to
     * {@code spaceId}, its source really belongs to that space
     * (JOIN), and the space is owned by {@code ownerSubject}. Any
     * mismatch returns {@code null} (404, anti-probing).
     */
    @Select("SELECT ij.* "
            + "FROM ingestion_job ij "
            + "JOIN source s "
            + "  ON s.id = ij.source_id "
            + " AND s.space_id = ij.space_id "
            + "JOIN learning_space ls ON ls.id = ij.space_id "
            + "WHERE ij.id = #{jobId} "
            + "  AND ij.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    IngestionJob selectByIdSpaceOwner(@Param("jobId") Long jobId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);

    /**
     * Lists jobs of one owned source, newest first. The owner
     * predicate and the source↔space consistency are enforced IN SQL
     * via the JOINs — this method can never list jobs of a source
     * the caller does not own.
     */
    @Select("SELECT ij.* "
            + "FROM ingestion_job ij "
            + "JOIN source s "
            + "  ON s.id = ij.source_id "
            + " AND s.space_id = ij.space_id "
            + "JOIN learning_space ls ON ls.id = ij.space_id "
            + "WHERE ij.space_id = #{spaceId} "
            + "  AND ij.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY ij.created_at DESC, ij.id DESC")
    List<IngestionJob> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                                @Param("sourceId") Long sourceId,
                                                @Param("ownerSubject") String ownerSubject);

    /**
     * Terminal failure with diagnostics, in one statement so the status change
     * can never be persisted without the error code/message that caused it.
     * {@code updateById} skips nulls, which previously left runs reporting the
     * previous attempt's failure.
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job "
                    + "SET status = #{status}, stage = #{stage}, progress_percent = #{progress}, "
                    + "    error_code = #{errorCode}, error_message = #{errorMessage}, "
                    + "    finished_at = #{now}, updated_at = #{now} "
                    + "WHERE id = #{id}")
    int markTerminalFailure(@Param("id") Long id,
                            @Param("status") String status,
                            @Param("stage") String stage,
                            @Param("progress") int progress,
                            @Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage,
                            @Param("now") java.time.LocalDateTime now);

    /**
     * Lease renewal. A worker that stops beating inside
     * {@code aistudy.ingestion.worker.stale-lease} is the only signal that lets
     * recovery requeue its job, so long-running jobs must keep refreshing here
     * or two workers can end up on the same row.
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job SET claimed_at = #{now}, updated_at = #{now} "
                    + "WHERE id = #{id} AND claimed_by = #{workerId} "
                    + "  AND status IN ('IMPORTING','EXTRACTING','STRUCTURING','AI_PROCESSING')")
    int refreshClaim(@Param("id") Long id,
                     @Param("workerId") String workerId,
                     @Param("now") java.time.LocalDateTime now);

    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job "
                    + "SET status = #{status}, stage = #{stage}, updated_at = #{updatedAt}, retry_count = #{retryCount} "
                    + "WHERE id = #{id} AND space_id = #{spaceId}")
    int retryByIdAndSpace(@Param("id") Long id,
                          @Param("spaceId") Long spaceId,
                          @Param("status") String status,
                          @Param("stage") String stage,
                          @Param("updatedAt") java.time.LocalDateTime updatedAt,
                          @Param("retryCount") Integer retryCount);

    /**
     * Atomic claim: only one worker may move QUEUED → IMPORTING.
     * Returns 1 when this worker won the claim, 0 otherwise.
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job "
                    + "SET status = 'IMPORTING', stage = 'IMPORT', progress_percent = 5, "
                    + "    claimed_by = #{workerId}, claimed_at = #{now}, "
                    + "    started_at = COALESCE(started_at, #{now}), "
                    + "    updated_at = #{now} "
                    + "WHERE id = #{id} AND status = 'QUEUED' "
                    + "  AND (claimed_by IS NULL OR claimed_at IS NULL "
                    + "       OR claimed_at < #{staleBefore})")
    int claimQueued(@Param("id") Long id,
                    @Param("workerId") String workerId,
                    @Param("now") java.time.LocalDateTime now,
                    @Param("staleBefore") java.time.LocalDateTime staleBefore);

    /** Re-queue jobs stuck in non-terminal PROCESSING-like states after crash. */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job "
                    + "SET status = 'QUEUED', claimed_by = NULL, claimed_at = NULL, updated_at = #{now} "
                    + "WHERE status IN ('IMPORTING','EXTRACTING','STRUCTURING','AI_PROCESSING') "
                    + "  AND (claimed_at IS NULL OR claimed_at < #{staleBefore})")
    int requeueStaleProcessing(@Param("now") java.time.LocalDateTime now,
                               @Param("staleBefore") java.time.LocalDateTime staleBefore);

    @org.apache.ibatis.annotations.Select(
            "SELECT ij.* FROM ingestion_job ij "
                    + "WHERE ij.status = 'QUEUED' "
                    + "ORDER BY ij.created_at ASC, ij.id ASC LIMIT #{limit}")
    List<IngestionJob> selectQueued(@Param("limit") int limit);

    /** Mark last successful stage for stage-resume retries. */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job SET last_stage_status = #{stage}, updated_at = #{now} "
                    + "WHERE id = #{id}")
    int markStageComplete(@Param("id") Long id,
                          @Param("stage") String stage,
                          @Param("now") java.time.LocalDateTime now);

    /** Stage-resume retry: requeue without wiping completed stage marker. */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job "
                    + "SET status = 'QUEUED', claimed_by = NULL, claimed_at = NULL, "
                    + "    error_code = NULL, error_message = NULL, "
                    + "    retry_count = #{retryCount}, updated_at = #{now} "
                    + "WHERE id = #{id} AND space_id = #{spaceId} "
                    + "  AND status IN ('FAILED','PARTIAL_FAILED')")
    int requeueForStageRetry(@Param("id") Long id,
                             @Param("spaceId") Long spaceId,
                             @Param("retryCount") int retryCount,
                             @Param("now") java.time.LocalDateTime now);

    /**
     * ADMIN governance retry. Also accepts a QUEUED row, because governance
     * may need to re-dispatch a job that was never picked up.
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job "
                    + "SET status = 'QUEUED', claimed_by = NULL, claimed_at = NULL, "
                    + "    error_code = NULL, error_message = NULL, "
                    + "    retry_count = COALESCE(retry_count, 0) + 1, updated_at = #{now} "
                    + "WHERE id = #{id} AND space_id = #{spaceId} "
                    + "  AND status IN ('FAILED','PARTIAL_FAILED','QUEUED')")
    int requeueForAdminRetry(@Param("id") Long id,
                             @Param("spaceId") Long spaceId,
                             @Param("now") java.time.LocalDateTime now);

    /** Job history of one source, newest first (ADMIN governance list). */
    @Select("SELECT ij.*, ls.owner_subject FROM ingestion_job ij "
            + "JOIN learning_space ls ON ls.id = ij.space_id "
            + "WHERE ij.source_id = #{sourceId} AND ij.space_id = #{spaceId} "
            + "ORDER BY ij.created_at DESC, ij.id DESC")
    List<Map<String, Object>> selectAdminBySpaceAndSource(@Param("sourceId") Long sourceId,
                                                          @Param("spaceId") Long spaceId);
}
