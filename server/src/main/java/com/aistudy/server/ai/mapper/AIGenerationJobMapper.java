package com.aistudy.server.ai.mapper;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * §8.6 — {@link AIGenerationJob} mapper.
 *
 * <p>Reads are split in two families and the split is a security boundary:
 *
 * <ul>
 *   <li><b>owner reads</b> ({@link #selectByIdSpaceAndRequester},
 *       {@link #selectBySpaceAndRequester},
 *       {@link #countBySpaceAndRequester}) bind {@code space_id} <em>and</em>
 *       {@code requester} in SQL. The space path segment is not a filter the
 *       service may forget: an owner-facing read that omits it is what let a
 *       user list their Space B jobs while browsing Space A (F-21).</li>
 *   <li><b>ADMIN governance reads</b> ({@link #selectAllAdmin}) are
 *       deliberately unbound and only reachable from the ADMIN-gated
 *       controller.</li>
 * </ul>
 *
 * <p>Writes that move a job out of {@code PENDING} are guarded by the lease:
 * {@link #claimPending} is the atomic claim, and every non-claim write after
 * it must present the {@code claimToken} that claim minted. A statement that
 * matches zero rows therefore means "this worker no longer owns the job",
 * never "the update was uninteresting".
 */
@Mapper
public interface AIGenerationJobMapper extends BaseMapper<AIGenerationJob> {

    /**
     * Owner detail read. Returns the job only when it exists, sits in
     * {@code spaceId} and was requested by {@code requester}; any mismatch
     * yields {@code null} so the caller answers 404 and cannot probe for the
     * existence of a job in another space.
     */
    @Select("SELECT * FROM ai_generation_job "
            + "WHERE id = #{jobId} AND space_id = #{spaceId} AND requester = #{requester} LIMIT 1")
    AIGenerationJob selectByIdSpaceAndRequester(@Param("jobId") Long jobId,
                                                @Param("spaceId") Long spaceId,
                                                @Param("requester") String requester);

    /**
     * Owner list read for one space, newest first. Never returns rows of
     * another space, even for the same requester.
     */
    @Select("SELECT * FROM ai_generation_job "
            + "WHERE space_id = #{spaceId} AND requester = #{requester} "
            + "ORDER BY created_at DESC, id DESC "
            + "LIMIT #{limit} OFFSET #{offset}")
    List<AIGenerationJob> selectBySpaceAndRequester(@Param("spaceId") Long spaceId,
                                                    @Param("requester") String requester,
                                                    @Param("offset") int offset,
                                                    @Param("limit") int limit);

    /**
     * Total element count for {@link #selectBySpaceAndRequester} with the
     * {@code spaceId} and {@code requester} predicates applied, so a page
     * never advertises a total wider than the list it scopes.
     */
    @Select("SELECT COUNT(*) FROM ai_generation_job "
            + "WHERE space_id = #{spaceId} AND requester = #{requester}")
    long countBySpaceAndRequester(@Param("spaceId") Long spaceId,
                                  @Param("requester") String requester);

    /**
     * Conditional progress/status write. Bound to {@code claimToken} so a
     * worker whose lease was already reclaimed cannot keep reporting progress
     * for a job another worker now owns.
     */
    @Update("UPDATE ai_generation_job "
            + "SET status = #{status}, progress = #{progress}, error_code = #{errorCode}, "
            + " safe_message = #{safeMessage}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = #{expectedStatus} AND claim_token = #{claimToken}")
    int updateStatus(@Param("id") Long id,
                     @Param("expectedStatus") String expectedStatus,
                     @Param("status") String status,
                     @Param("progress") Integer progress,
                     @Param("errorCode") String errorCode,
                     @Param("safeMessage") String safeMessage,
                     @Param("updatedAt") LocalDateTime updatedAt,
                     @Param("claimToken") String claimToken);

    /**
     * Terminal success write. Counts are assigned (never accumulated) from
     * what the run actually persisted, and the statement is conditional on
     * both the expected status and the lease token: an abandoned worker must
     * not be able to flip a job another worker already finished.
     *
     * @return {@code 1} when this worker still owns the job and finalized it,
     *         {@code 0} when the lease or the status moved on without it
     */
    @Update("UPDATE ai_generation_job "
            + "SET status = #{status}, progress = #{progress}, "
            + " success_count = #{successCount}, failure_count = #{failureCount}, "
            + " error_code = NULL, safe_message = NULL, "
            + " finished_at = #{finishedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = #{expectedStatus} AND claim_token = #{claimToken}")
    int finishJob(@Param("id") Long id,
                  @Param("expectedStatus") String expectedStatus,
                  @Param("status") String status,
                  @Param("progress") Integer progress,
                  @Param("successCount") Integer successCount,
                  @Param("failureCount") Integer failureCount,
                  @Param("finishedAt") LocalDateTime finishedAt,
                  @Param("updatedAt") LocalDateTime updatedAt,
                  @Param("claimToken") String claimToken);

    /**
     * Terminal failure write with its diagnostics in the SAME statement
     * (audit A-06 / ingestion parity): a FAILED row can never be persisted
     * without the error code and client-safe message that caused it, which is
     * what a split {@code updateStatus} + {@code finishJob} used to allow.
     */
    @Update("UPDATE ai_generation_job "
            + "SET status = 'FAILED', progress = #{progress}, "
            + " success_count = #{successCount}, failure_count = #{failureCount}, "
            + " error_code = #{errorCode}, safe_message = #{safeMessage}, "
            + " finished_at = #{finishedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = 'PROCESSING' AND claim_token = #{claimToken}")
    int markTerminalFailure(@Param("id") Long id,
                            @Param("progress") Integer progress,
                            @Param("successCount") Integer successCount,
                            @Param("failureCount") Integer failureCount,
                            @Param("errorCode") String errorCode,
                            @Param("safeMessage") String safeMessage,
                            @Param("finishedAt") LocalDateTime finishedAt,
                            @Param("updatedAt") LocalDateTime updatedAt,
                            @Param("claimToken") String claimToken);

    /**
     * Owner retry: FAILED → PENDING, and the row is reset to a genuinely
     * unclaimed state — lease cleared so the next claim mints a new token,
     * and per-run counters cleared so a fresh attempt does not inherit the
     * previous attempt's counts.
     *
     * <p>{@code FAILED} alone is the guard: a job another worker still holds
     * is never {@code FAILED}, so a retry can no longer steal a live lease.
     */
    @Update("UPDATE ai_generation_job "
            + "SET status = 'PENDING', progress = 0, retry_count = retry_count + 1, "
            + " success_count = 0, failure_count = 0, "
            + " error_code = NULL, safe_message = NULL, started_at = NULL, finished_at = NULL, "
            + " claimed_by = NULL, claimed_at = NULL, claim_token = NULL, "
            + " updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = 'FAILED'")
    int retryFailed(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * ADMIN governance list. Intentionally not owner-bound; reachable only
     * from the ADMIN-gated controller.
     */
    @Select("SELECT * FROM ai_generation_job "
            + "WHERE (#{status} IS NULL OR status = #{status}) "
            + "  AND (#{spaceId} IS NULL OR space_id = #{spaceId}) "
            + "ORDER BY created_at DESC, id DESC")
    List<AIGenerationJob> selectAllAdmin(@Param("status") String status,
                                         @Param("spaceId") Long spaceId);

    /**
     * Atomic claim: exactly one worker moves PENDING → PROCESSING and mints
     * the lease token for that attempt (mirrors
     * {@code IngestionJobMapper.claimQueued}).
     *
     * <p>The trailing predicate rejects a row that still carries a live claim:
     * recovery clears the lease, so a non-NULL {@code claimed_at} inside the
     * lease window means another worker is still on it.
     *
     * @param token       fresh per-claim identity; returned writes must echo it
     * @param staleBefore lease cut-off; an older claim is treated as abandoned
     * @return {@code 1} for the winning worker, {@code 0} for everyone else
     */
    @Update("UPDATE ai_generation_job "
            + "SET status = 'PROCESSING', progress = 1, "
            + " claimed_by = #{workerId}, claimed_at = #{now}, claim_token = #{token}, "
            + " started_at = COALESCE(started_at, #{now}), updated_at = #{now} "
            + "WHERE id = #{id} AND status = 'PENDING' "
            + "  AND (claimed_by IS NULL OR claim_token IS NULL OR claimed_at IS NULL "
            + "       OR claimed_at < #{staleBefore})")
    int claimPending(@Param("id") Long id,
                     @Param("workerId") String workerId,
                     @Param("token") String token,
                     @Param("now") LocalDateTime now,
                     @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * Lease heartbeat (F-29). Renews {@code claimed_at} for the current holder
     * only; {@code 0} rows is the worker's signal that recovery has already
     * taken the job away, so it must stop calling the provider and must not
     * write any result for it.
     *
     * <p>{@code claim_token} is in the predicate rather than just
     * {@code claimed_by}: a worker id is process-wide and survives a requeue,
     * so the holder name alone cannot distinguish this attempt from the
     * attempt recovery just handed to another worker.
     */
    @Update("UPDATE ai_generation_job "
            + "SET claimed_at = #{now}, updated_at = #{now} "
            + "WHERE id = #{id} AND claimed_by = #{workerId} AND claim_token = #{token} "
            + "  AND status = 'PROCESSING'")
    int refreshClaim(@Param("id") Long id,
                     @Param("workerId") String workerId,
                     @Param("token") String token,
                     @Param("now") LocalDateTime now);

    @Select("SELECT * FROM ai_generation_job WHERE status = 'PENDING' "
            + "ORDER BY created_at ASC, id ASC LIMIT #{limit}")
    List<AIGenerationJob> selectPendingBatch(@Param("limit") int limit);

    /**
     * Crash recovery: requeues PROCESSING jobs whose lease went stale, which
     * covers both a worker that died mid-run and one that is wedged without
     * beating. The lease is cleared so the next {@link #claimPending} wins
     * cleanly and mints a new token, orphaning the abandoned attempt.
     *
     * @param staleBefore {@code now - aistudy.ai.worker.stale-lease-ms}
     */
    @Update("UPDATE ai_generation_job "
            + "SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL, "
            + " claim_token = NULL, updated_at = #{now} "
            + "WHERE status = 'PROCESSING' AND (claimed_at IS NULL OR claimed_at < #{staleBefore})")
    int requeueStaleProcessing(@Param("now") LocalDateTime now,
                               @Param("staleBefore") LocalDateTime staleBefore);
}
