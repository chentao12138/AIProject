package com.aistudy.server.ai.service;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.mapper.AIGenerationJobMapper;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * §8.6 — AIGenerationJob application service.
 *
 * <p>Worker-driven state transitions: PENDING → PROCESSING → COMPLETED/FAILED.
 * Retry is allowed from FAILED back to PENDING. Progress is integer 0..100.
 * AI output is always DRAFT; this service never publishes anything.
 *
 * <h2>Visibility (F-21)</h2>
 * Every owner-facing read and write is bound to <em>both</em> {@code spaceId}
 * and the JWT subject ({@code requester}). A job created in Space B is not
 * listable, countable, readable or retryable through the Space A path, and a
 * miss is reported as 404 with the same text as "does not exist" — a 403, or a
 * different message, would confirm that the row exists in some other space.
 *
 * <h2>Lease (F-29)</h2>
 * {@code PROCESSING} means "a worker holds a lease", and the lease is a
 * {@code claimToken} minted per claim, not merely a worker name:
 * <ul>
 *   <li>{@link #claim} is the only PENDING → PROCESSING edge and it is one
 *       conditional UPDATE, so two workers cannot both enter it;</li>
 *   <li>{@link #renewLease} pushes {@code claimed_at} forward. Recovery only
 *       requeues rows whose lease is older than
 *       {@code aistudy.ai.worker.stale-lease-ms}, so a worker that keeps
 *       beating cannot be double-executed;</li>
 *   <li>every post-claim write — progress, success and failure alike — is
 *       conditional on that token. An attempt whose token no longer matches is
 *       a *lost* attempt: it may not report progress, may not write counts, and
 *       above all may not finalize a job the recovery tick already handed to
 *       another worker. Without the token, {@code status='PROCESSING'} looks
 *       identical to both holders and the abandoned one would write a second
 *       terminal COMPLETED (and a second draft result set) for the same job.</li>
 * </ul>
 *
 * <h2>Dispatch (audit A-06)</h2>
 * Work is handed to the bounded worker pool <em>after</em> the caller's
 * transaction commits, never inside it: submitting before commit let a worker
 * look for a row the enclosing transaction had not yet made visible, and a
 * rolled-back create would still have started a real AI run. A dispatch that
 * finds no free slot is swallowed — the row stays PENDING (an honest queued
 * state, never a fake PROCESSING) and the recovery tick re-dispatches it.
 */
@Service
public class AIGenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(AIGenerationJobService.class);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    /** Persisted in {@code error_code}; never raw provider text. */
    public static final String ERROR_NO_OUTPUT = "AI_JOB_NO_OUTPUT";
    public static final String ERROR_UNSUPPORTED_TYPE = "AI_JOB_TYPE_UNSUPPORTED";
    public static final String ERROR_LEASE_LOST = "AI_JOB_LEASE_LOST";
    public static final String ERROR_PROCESSING = "AI_PROCESSING_ERROR";

    /**
     * Default lease window. Comfortably longer than the provider read timeout
     * (60s) plus the surrounding DB work, so one generation step cannot
     * outlive the lease it is running under.
     */
    static final Duration DEFAULT_STALE_LEASE = Duration.ofMinutes(30);

    /** One-fifth of the lease: a beat that misses still leaves four retries before recovery. */
    static final Duration DEFAULT_HEARTBEAT = Duration.ofMinutes(5);

    private final AIGenerationJobMapper aiGenerationJobMapper;
    private final GenerationJobDispatcher dispatcher;
    private final Duration staleLease;
    private final Duration heartbeat;

    public AIGenerationJobService(AIGenerationJobMapper aiGenerationJobMapper,
                                  @Lazy GenerationJobDispatcher dispatcher,
                                  @Value("${aistudy.ai.worker.stale-lease-ms:1800000}") long staleLeaseMs,
                                  @Value("${aistudy.ai.worker.heartbeat-ms:300000}") long heartbeatMs) {
        this.aiGenerationJobMapper = aiGenerationJobMapper;
        this.dispatcher = dispatcher;
        this.staleLease = Duration.ofMillis(Math.max(1_000L, staleLeaseMs));
        Duration requestedHeartbeat = Duration.ofMillis(Math.max(200L, heartbeatMs));
        // A heartbeat at or beyond the lease never renews anything: recovery
        // would requeue the job on the same tick the worker beats, which is the
        // duplicate-execution this whole design is meant to remove. Cap it at a
        // third of the lease instead of trusting the configured pair.
        Duration ceiling = Duration.ofMillis(Math.max(200L, this.staleLease.toMillis() / 3));
        this.heartbeat = requestedHeartbeat.compareTo(ceiling) > 0 ? ceiling : requestedHeartbeat;
    }

    /**
     * One page of the owner's jobs inside one space, plus the total that
     * predicate matches. {@code totalElements} is counted with the same
     * space + requester binding as {@code content}, so a client can never see
     * a count wider than the list it was given.
     */
    public record JobPage(List<AIGenerationJob> content, int page, int size,
                          long totalElements, int totalPages) {
    }

    /**
     * Persists a PENDING job and schedules the run after commit.
     *
     * @param spaceId      required; it is the visibility scope of every later read
     * @param requester    JWT subject, never a client-supplied string
     * @return the persisted row; {@code status} is {@code PENDING} even if the
     *         worker pool is saturated
     */
    @Transactional
    public AIGenerationJob create(String jobType, String requester, Long spaceId,
                                 Long sourceId, Long revisionId, Long knowledgePointId) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        AIGenerationJob job = new AIGenerationJob();
        job.setJobType(jobType);
        job.setStatus(STATUS_PENDING);
        job.setProgress(0);
        job.setRequester(requester);
        job.setSpaceId(spaceId);
        job.setSourceId(sourceId);
        job.setRevisionId(revisionId);
        job.setKnowledgePointId(knowledgePointId);
        job.setSuccessCount(0);
        job.setFailureCount(0);
        job.setRetryCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        aiGenerationJobMapper.insert(job);
        dispatchAfterCommit(job.getId());
        return job;
    }

    /** Backward-compatible create without knowledgePointId. */
    public AIGenerationJob create(String jobType, String requester, Long spaceId,
                                  Long sourceId, Long revisionId) {
        return create(jobType, requester, spaceId, sourceId, revisionId, null);
    }

    /**
     * Atomic claim for workers.
     *
     * @return the freshly minted lease token when this worker won the job, or
     *         {@code null} when another holder already has it (or the row left
     *         PENDING). The token must be passed back to every later write.
     */
    @Transactional
    public String claim(Long jobId, String workerId) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String token = newClaimToken();
        int claimed = aiGenerationJobMapper.claimPending(
                jobId, workerId, token, now, now.minus(staleLease));
        return claimed == 1 ? token : null;
    }

    /**
     * Extends this attempt's lease. Call it before every expensive provider
     * step, not just periodically: the return value is the ownership check
     * that makes the call safe to start at all.
     *
     * @return {@code true} while the caller still owns the job; {@code false}
     *         once recovery has requeued it, in which case the caller must not
     *         invoke the provider and must not write anything for this job
     */
    public boolean renewLease(Long jobId, String workerId, String claimToken) {
        return aiGenerationJobMapper.refreshClaim(jobId, workerId, claimToken,
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)) == 1;
    }

    /**
     * How long a running worker may go without {@link #renewLease} before
     * recovery may requeue its job. Exposed so the worker and the recovery
     * runner cannot drift apart on the same lease.
     */
    public Duration staleLease() {
        return staleLease;
    }

    /** Lease renewal interval a running job should aim for. Always &lt; {@link #staleLease()}. */
    public Duration heartbeatInterval() {
        return heartbeat;
    }

    /**
     * Progress tick for the current holder. Does not renew the lease — that is
     * {@link #renewLease}, so a job cannot look alive by reporting progress
     * while its claim has already been reclaimed.
     *
     * @return {@code false} when the lease was lost, so the caller abandons the run
     */
    public boolean reportProgress(Long jobId, String workerId, String claimToken, int progress) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return aiGenerationJobMapper.updateStatus(jobId, STATUS_PROCESSING, STATUS_PROCESSING,
                Math.min(99, Math.max(0, progress)), null, null, now, claimToken) == 1;
    }

    /**
     * Terminal success/failure write by the current lease holder.
     *
     * @param successCount entities the run actually persisted — never an
     *                     off-by-one of a previous value, and never incremented
     *                     a second time by the caller
     * @param failureCount generation targets that produced no output (0 or 1:
     *                     one job row addresses exactly one target)
     * @return the persisted row
     * @throws ApiException 409 when the lease or status moved without this caller
     */
    @Transactional
    public AIGenerationJob finish(Long jobId, String claimToken, int successCount, int failureCount) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String finalStatus = failureCount > 0 ? STATUS_FAILED : STATUS_COMPLETED;
        int updated = aiGenerationJobMapper.finishJob(jobId, STATUS_PROCESSING, finalStatus, 100,
                Math.max(0, successCount), Math.max(0, failureCount), now, now, claimToken);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCodes.CONFLICT,
                    "job state or lease changed concurrently");
        }
        return requireById(jobId);
    }

    /**
     * Terminal FAILED write: status, counters, error code and client-safe
     * message land in one conditional UPDATE (ingestion parity, audit I-01), so
     * a failure can never be persisted as a bare status change.
     *
     * @return {@code true} when this caller still owned the job and wrote the failure
     */
    @Transactional
    public boolean fail(Long jobId, String claimToken, String errorCode, String safeMessage,
                        int successCount, int failureCount) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return aiGenerationJobMapper.markTerminalFailure(jobId, 100,
                Math.max(0, successCount), Math.max(0, failureCount),
                errorCode, safeMessage, now, now, claimToken) == 1;
    }

    /**
     * Never persist raw provider/internal exception text to clients or to the
     * job row.
     */
    public static String sanitizeSafeMessage(Throwable t) {
        if (t == null) {
            return "AI generation failed";
        }
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return "AI generation failed";
        }
        String lower = msg.toLowerCase();
        if (lower.contains("api key") || lower.contains("authorization")
                || lower.contains("bearer ") || lower.contains("secret")) {
            return "AI provider configuration error";
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    /**
     * Space-scoped page of the caller's own jobs.
     *
     * @param page zero-based; negative values are treated as the first page
     * @param size clamped to 1..100 by the caller
     */
    public JobPage listMine(String requester, Long spaceId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        long total = aiGenerationJobMapper.countBySpaceAndRequester(spaceId, requester);
        List<AIGenerationJob> content = aiGenerationJobMapper.selectBySpaceAndRequester(
                spaceId, requester, safePage * safeSize, safeSize);
        return new JobPage(content == null ? List.of() : content, safePage, safeSize, total,
                (int) Math.ceil((double) total / safeSize));
    }

    /**
     * Owner detail read.
     *
     * @return the job, or {@code null} when it does not exist <em>or</em> lives
     *         in another space — deliberately the same answer for both
     */
    public AIGenerationJob getMine(String requester, Long spaceId, Long jobId) {
        if (jobId == null) {
            return null;
        }
        return aiGenerationJobMapper.selectByIdSpaceAndRequester(jobId, spaceId, requester);
    }

    /**
     * FAILED → PENDING for the owner of the job's space, clearing the lease so
     * the next claim mints a new token and resetting the per-run counters.
     *
     * @throws ApiException 404 for a job outside {@code spaceId} or not owned by
     *                      {@code requester}; 409 for a job that is not FAILED
     */
    @Transactional
    public AIGenerationJob retry(String requester, Long spaceId, Long jobId) {
        AIGenerationJob job = aiGenerationJobMapper.selectByIdSpaceAndRequester(jobId, spaceId, requester);
        if (job == null) {
            // Same text as "does not exist": a 403 here would leak that the id
            // is real and merely belongs to another space.
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND,
                    "AIGenerationJob not found");
        }
        if (!STATUS_FAILED.equals(job.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCodes.CONFLICT,
                    "only FAILED jobs can be retried: " + job.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (aiGenerationJobMapper.retryFailed(jobId, now) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCodes.CONFLICT,
                    "job state changed concurrently");
        }
        dispatchAfterCommit(jobId);
        return requireById(jobId);
    }

    /**
     * Startup and scheduled recovery (F-29 + A-06): requeues PROCESSING jobs
     * whose lease went stale, then dispatches everything sitting in PENDING.
     *
     * <p>Requeue and drain belong together: a job requeued without a dispatch
     * only becomes runnable again at the next create, and a drain without
     * requeue would leave an abandoned lease held forever.
     *
     * @return jobs requeued plus jobs dispatched
     */
    public int recoverStaleJobs() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int requeued = aiGenerationJobMapper.requeueStaleProcessing(now, now.minus(staleLease));
        if (requeued > 0) {
            log.info("AI generation recovery requeued {} stale jobs", requeued);
        }
        List<AIGenerationJob> pending = aiGenerationJobMapper.selectPendingBatch(50);
        int dispatched = 0;
        if (pending != null) {
            for (AIGenerationJob job : pending) {
                dispatcher.dispatch(job.getId());
                dispatched++;
            }
        }
        return requeued + dispatched;
    }

    /**
     * PENDING rows for dispatch. Unscoped on purpose: only the worker reads
     * this, and a job is never returned to an owner through it.
     */
    public List<AIGenerationJob> listPendingBatch(int limit) {
        return aiGenerationJobMapper.selectPendingBatch(Math.max(1, limit));
    }

    public List<AIGenerationJob> listPending() {
        return aiGenerationJobMapper.selectAllAdmin(STATUS_PENDING, null);
    }

    /** Lease identity of a row, for tests and diagnostics. */
    public AIGenerationJob findById(Long jobId) {
        return jobId == null ? null : aiGenerationJobMapper.selectById(jobId);
    }

    /** New claim identity; 36 chars, always fits {@code claim_token VARCHAR(64)}. */
    static String newClaimToken() {
        return UUID.randomUUID().toString();
    }

    private AIGenerationJob requireById(Long jobId) {
        AIGenerationJob job = aiGenerationJobMapper.selectById(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND,
                    "AIGenerationJob not found");
        }
        return job;
    }

    /**
     * Hands the job to the bounded worker pool after the caller's transaction
     * commits; a rolled-back create must never have started a run.
     *
     * <p>Outside a transaction (worker re-dispatch, tests) it dispatches now.
     * A rejection inside the dispatcher is not an error the caller sees: the
     * row stays PENDING and the recovery tick retries it.
     */
    private void dispatchAfterCommit(Long jobId) {
        if (jobId == null) {
            return;
        }
        Runnable dispatch = () -> {
            try {
                dispatcher.dispatch(jobId);
            } catch (RuntimeException ex) {
                log.warn("AI generation job {} queued for later dispatch: {}", jobId, ex.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }
}
