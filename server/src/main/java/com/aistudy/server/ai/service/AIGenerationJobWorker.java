package com.aistudy.server.ai.service;

import com.aistudy.server.ai.content.AiContentStructureService;
import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.knowledge.AiKnowledgeExtractionService;
import com.aistudy.server.ai.question.AiQuestionGenerationService;
import com.aistudy.server.ai.source.AiSourceDiagnosisService;
import com.aistudy.server.common.problem.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded AI generation worker (architecture.md §6.2, ingestion-lane parity).
 *
 * <p>Uses DB atomic claim so only one worker processes a job; never stores raw
 * provider secrets/errors. It owns the execution half of the lease contract:
 * the claim that mints a token, the renewal that proves the token is still
 * live, and the terminal write that is only accepted while it is.
 *
 * <p>Job types are declared here as {@link #SUPPORTED_JOB_TYPES} and the
 * controller allow-list is derived from it, so an accepted type with no handler
 * — the {@code exam-diagnosis-narrative} bug (F-22) — cannot come back through
 * drift between the two files.
 *
 * <h2>Failure modes</h2>
 * <ul>
 *   <li>pool saturated → the task is refused here and the row keeps
 *       {@code PENDING}; nothing is ever marked PROCESSING on a hope.</li>
 *   <li>lease reclaimed by recovery mid-run → {@link AiJobFailureException.LeaseLostException},
 *       logged, and <em>no</em> write: the job belongs to another attempt now.</li>
 *   <li>handler threw / produced no entity → one conditional FAILED UPDATE with
 *       a named error code and {@code successCount=0, failureCount=1}.</li>
 *   <li>type with no handler (legacy rows) → FAILED {@code AI_JOB_TYPE_UNSUPPORTED},
 *       not an opaque internal error.</li>
 * </ul>
 */
@Component
public class AIGenerationJobWorker implements GenerationJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AIGenerationJobWorker.class);

    public static final String JOB_TYPE_CONTENT_STRUCTURE = "content-structure";
    public static final String JOB_TYPE_KNOWLEDGE_EXTRACTION = "knowledge-extraction";
    public static final String JOB_TYPE_QUESTION_GENERATION = "question-generation";

    /**
     * The complete set of job types this build can actually run. Everything
     * else must be refused at the boundary, because accepting a type with no
     * handler only converts a client mistake into a guaranteed FAILED job.
     *
     * <p>{@code exam-diagnosis-narrative} is deliberately absent: no narrative
     * service exists yet (see {@link #execute}), so it is rejected with
     * {@code AI_JOB_TYPE_UNSUPPORTED} instead of being queued to fail.
     */
    public static final Set<String> SUPPORTED_JOB_TYPES = Set.of(
            JOB_TYPE_CONTENT_STRUCTURE,
            JOB_TYPE_KNOWLEDGE_EXTRACTION,
            JOB_TYPE_QUESTION_GENERATION);

    /**
     * Process-unique so two workers (rolling restart, two instances) never look
     * like the same lease holder. The token, not this id, is what arbitrates.
     */
    private final String workerId =
            "ai-generation-worker-" + UUID.randomUUID().toString().substring(0, 8);

    private final AIGenerationJobService aiGenerationJobService;
    private final AiContentStructureService aiContentStructureService;
    private final AiKnowledgeExtractionService aiKnowledgeExtractionService;
    private final AiQuestionGenerationService aiQuestionGenerationService;
    private final AiSourceDiagnosisService aiSourceDiagnosisService;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;
    private final int drainBatchSize;

    public AIGenerationJobWorker(AIGenerationJobService aiGenerationJobService,
                                 AiContentStructureService aiContentStructureService,
                                 AiKnowledgeExtractionService aiKnowledgeExtractionService,
                                 AiQuestionGenerationService aiQuestionGenerationService,
                                 AiSourceDiagnosisService aiSourceDiagnosisService,
                                 @Qualifier("aiGenerationWorkerExecutor")
                                 org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor,
                                 @Value("${aistudy.ai.worker.drain-batch-size:50}") int drainBatchSize) {
        this.aiGenerationJobService = aiGenerationJobService;
        this.aiContentStructureService = aiContentStructureService;
        this.aiKnowledgeExtractionService = aiKnowledgeExtractionService;
        this.aiQuestionGenerationService = aiQuestionGenerationService;
        this.aiSourceDiagnosisService = aiSourceDiagnosisService;
        this.executor = executor;
        this.drainBatchSize = Math.max(1, drainBatchSize);
    }

    /**
     * Non-blocking hand-off to the bounded pool.
     *
     * <p>Rejection is logged and swallowed on purpose: the row is still
     * {@code PENDING} at this point, which is the truthful state, and the next
     * recovery tick dispatches it again. Reserving a slot by writing PROCESSING
     * first is what would make a saturated pool report work that never started.
     */
    @Override
    public void dispatch(Long jobId) {
        if (jobId == null) {
            return;
        }
        try {
            executor.execute(() -> processJob(jobId));
        } catch (RejectedExecutionException ex) {
            log.warn("AI generation job {} stays PENDING, worker pool saturated: {}",
                    jobId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("AI generation job {} stays PENDING, dispatch refused: {}",
                    jobId, ex.getMessage());
        }
    }

    /**
     * One run attempt for one job row.
     *
     * <p>Claim first and only then touch the provider: a lost claim means this
     * worker never owned the row, so it must not generate anything. Every write
     * after the claim carries the claim token, so an attempt that outlived its
     * lease cannot finalize the job recovery already handed over.
     */
    void processJob(Long jobId) {
        AIGenerationJob job = aiGenerationJobService.findById(jobId);
        if (job == null || !AIGenerationJobService.STATUS_PENDING.equals(job.getStatus())) {
            return;
        }
        String claimToken = aiGenerationJobService.claim(jobId, workerId);
        if (claimToken == null) {
            // Another worker won the atomic claim, or the row left PENDING.
            return;
        }
        job = aiGenerationJobService.findById(jobId);
        try {
            Outcome outcome = execute(job, claimToken);
            aiGenerationJobService.finish(jobId, claimToken,
                    outcome.successCount(), outcome.failureCount());
        } catch (AiJobFailureException.LeaseLostException lost) {
            log.warn("AI generation job {} abandoned: lease reclaimed by recovery, "
                    + "no result written", jobId);
        } catch (ApiException conflict) {
            // finish() rejected the terminal write because the token/status
            // moved on; this attempt is stale, so it must not overwrite the
            // winner's row either.
            log.warn("AI generation job {} terminal write refused (lease or status moved): {}",
                    jobId, conflict.getMessage());
        } catch (AiJobFailureException failure) {
            writeFailure(jobId, claimToken, failure.errorCode(), failure.safeMessage());
        } catch (RuntimeException unexpected) {
            log.error("AI generation job {} failed", jobId, unexpected);
            writeFailure(jobId, claimToken, AIGenerationJobService.ERROR_PROCESSING,
                    AIGenerationJobService.sanitizeSafeMessage(unexpected));
        }
    }

    private void writeFailure(Long jobId, String claimToken, String errorCode, String safeMessage) {
        boolean written = aiGenerationJobService.fail(jobId, claimToken, errorCode, safeMessage,
                0, 1);
        if (!written) {
            log.warn("AI generation job {} failure not recorded: lease already moved to another worker",
                    jobId);
        }
    }

    /**
     * What a run persisted. Counts are the entities the handlers actually
     * wrote, returned to the caller once — the previous shape incremented the
     * job's stored count in the worker AND again in {@code finish()}, so every
     * successful job reported one more than it had created (F-22).
     *
     * @param successCount entities written by this run; never negative
     * @param failureCount targets that produced no entity: a job row addresses
     *                     exactly one target, so this is 0 or 1
     */
    record Outcome(int successCount, int failureCount) {

        static Outcome wrote(int entities) {
            return new Outcome(Math.max(0, entities), 0);
        }
    }

    /**
     * Runs the handler for one job type and reports the truthful entity count.
     *
     * @throws AiJobFailureException with a stable code when the type is unknown
     *                               or nothing was produced
     * @throws AiJobFailureException.LeaseLostException when the lease is gone,
     *                               before any provider call is made
     */
    Outcome execute(AIGenerationJob job, String claimToken) {
        String type = job.getJobType();
        if (type == null) {
            throw new AiJobFailureException(AIGenerationJobService.ERROR_UNSUPPORTED_TYPE,
                    "job has no jobType");
        }
        // The renewal doubles as the ownership probe: a step that outlived its
        // lease is never started, which is what keeps recovery from producing a
        // second draft result set for the same job.
        if (!aiGenerationJobService.renewLease(job.getId(), workerId, claimToken)) {
            throw new AiJobFailureException.LeaseLostException(job.getId());
        }
        aiGenerationJobService.reportProgress(job.getId(), workerId, claimToken, 50);

        return switch (type) {
            case JOB_TYPE_CONTENT_STRUCTURE -> {
                int count = aiContentStructureService.structureBlocks(
                        job.getRequester(), job.getSpaceId(), job.getSourceId());
                if (count == 0) {
                    throw new AiJobFailureException(AIGenerationJobService.ERROR_NO_OUTPUT,
                            "no blocks structured");
                }
                yield Outcome.wrote(count);
            }
            case JOB_TYPE_KNOWLEDGE_EXTRACTION -> {
                String text = aiSourceDiagnosisService.extractSourceText(
                        job.getRequester(), job.getSpaceId(), job.getSourceId());
                if (text == null || text.isBlank()) {
                    throw new AiJobFailureException(AIGenerationJobService.ERROR_NO_OUTPUT,
                            "source text is empty");
                }
                List<?> created = aiKnowledgeExtractionService.extract(
                        job.getRequester(), job.getSpaceId(), text);
                if (created.isEmpty()) {
                    throw new AiJobFailureException(AIGenerationJobService.ERROR_NO_OUTPUT,
                            "no knowledge points extracted");
                }
                yield Outcome.wrote(created.size());
            }
            case JOB_TYPE_QUESTION_GENERATION -> {
                // knowledgePointId is the semantic target (C-30); revisionId is
                // only accepted for rows created before that column existed.
                Long kpId = job.getKnowledgePointId() != null
                        ? job.getKnowledgePointId() : job.getRevisionId();
                if (kpId == null) {
                    throw new AiJobFailureException(AIGenerationJobService.ERROR_NO_OUTPUT,
                            "knowledgePointId is required for question-generation");
                }
                var question = aiQuestionGenerationService.generate(
                        job.getRequester(), job.getSpaceId(), kpId);
                if (question == null) {
                    throw new AiJobFailureException(AIGenerationJobService.ERROR_NO_OUTPUT,
                            "question generation produced no result");
                }
                yield Outcome.wrote(1);
            }
            // F-22: exam-diagnosis-narrative used to be accepted here with no
            // handler, so it fell through to an opaque IllegalStateException.
            // It is refused at the controller now; this branch only drains
            // rows that were already persisted while it was accepted.
            default -> throw new AiJobFailureException(AIGenerationJobService.ERROR_UNSUPPORTED_TYPE,
                    "no worker handler for jobType: " + type);
        };
    }

    /** Number of PENDING rows a single recovery tick may hand to the pool. */
    public int drainBatchSize() {
        return drainBatchSize;
    }

    /** Lease holder name this process writes into {@code claimed_by}. */
    public String workerId() {
        return workerId;
    }
}
