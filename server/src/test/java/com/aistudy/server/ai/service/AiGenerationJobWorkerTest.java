package com.aistudy.server.ai.service;

import com.aistudy.server.ai.content.AiContentStructureService;
import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.job.AiGenerationJobTableFake;
import com.aistudy.server.ai.knowledge.AiKnowledgeExtractionService;
import com.aistudy.server.ai.question.AiQuestionGenerationService;
import com.aistudy.server.ai.source.AiSourceDiagnosisService;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.question.entity.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-22 and F-29 at the execution boundary: every accepted job type must reach a
 * truthful terminal state carrying the count of what it actually wrote, and no
 * second worker may report a second result for the same job.
 *
 * <p>Runs in the worker's own package so a lost lease can be asserted at the
 * handler seam itself, and drives the real {@link AIGenerationJobService} over
 * an in-memory mapper so the lease is genuinely arbitraged rather than stubbed
 * into agreeing.
 */
class AiGenerationJobWorkerTest {

    private static final String USER = "user-1";
    private static final long SPACE = 10L;

    private AiGenerationJobTableFake table;
    private AIGenerationJobService service;
    private AiContentStructureService contentStructure;
    private AiKnowledgeExtractionService knowledgeExtraction;
    private AiQuestionGenerationService questionGeneration;
    private AiSourceDiagnosisService sourceDiagnosis;
    private AIGenerationJobWorker workerA;
    private AIGenerationJobWorker workerB;

    @BeforeEach
    void setUp() {
        table = new AiGenerationJobTableFake();
        service = new AIGenerationJobService(table.mapper(), jobId -> {
        }, 1_800_000L, 300_000L);
        contentStructure = Mockito.mock(AiContentStructureService.class);
        knowledgeExtraction = Mockito.mock(AiKnowledgeExtractionService.class);
        questionGeneration = Mockito.mock(AiQuestionGenerationService.class);
        sourceDiagnosis = Mockito.mock(AiSourceDiagnosisService.class);
        workerA = newWorker(new InlineTaskExecutor());
        workerB = newWorker(new InlineTaskExecutor());
    }

    private AIGenerationJobWorker newWorker(ThreadPoolTaskExecutor executor) {
        return new AIGenerationJobWorker(service, contentStructure, knowledgeExtraction,
                questionGeneration, sourceDiagnosis, executor, 50);
    }

    // ==================== F-22: truthful counts per accepted type ====================

    @Test
    void contentStructureReportsTheBlocksItWroteAndNotOneMore() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE);
        // The regression: the worker used to store success = row(0) + 1 and the
        // terminal write then persisted that again, so 7 blocks became 8.
        Mockito.when(contentStructure.structureBlocks(USER, SPACE, 5L)).thenReturn(7);

        workerA.dispatch(job.getId());

        AIGenerationJob done = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_COMPLETED, done.getStatus());
        assertEquals(7, done.getSuccessCount(), "exactly the entities the handler returned");
        assertEquals(0, done.getFailureCount());
        assertNull(done.getErrorCode());
        assertEquals(100, done.getProgress());
        assertNotNull(done.getFinishedAt());
    }

    @Test
    void knowledgeExtractionReportsEveryPersistedPointOnce() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_KNOWLEDGE_EXTRACTION);
        Mockito.when(sourceDiagnosis.extractSourceText(USER, SPACE, 5L)).thenReturn("chapter one");
        Mockito.when(knowledgeExtraction.extract(USER, SPACE, "chapter one"))
                .thenReturn(List.of(new KnowledgePoint(), new KnowledgePoint(),
                        new KnowledgePoint(), new KnowledgePoint()));

        workerA.dispatch(job.getId());

        AIGenerationJob done = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_COMPLETED, done.getStatus());
        assertEquals(4, done.getSuccessCount(), "one per knowledge point the run created");
        assertEquals(0, done.getFailureCount());
    }

    @Test
    void questionGenerationCountsTheOneDraftQuestionItWrote() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_QUESTION_GENERATION);
        table.row(job.getId()).setKnowledgePointId(77L);
        Mockito.when(questionGeneration.generate(USER, SPACE, 77L)).thenReturn(new Question());

        workerA.dispatch(job.getId());

        AIGenerationJob done = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_COMPLETED, done.getStatus());
        assertEquals(1, done.getSuccessCount());
        assertEquals(0, done.getFailureCount());
        Mockito.verify(questionGeneration).generate(USER, SPACE, 77L);
    }

    @Test
    void emptyHandlerOutputIsAFailureNotASuccessfulZeroRun() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE);
        Mockito.when(contentStructure.structureBlocks(USER, SPACE, 5L)).thenReturn(0);

        workerA.dispatch(job.getId());

        AIGenerationJob failed = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_FAILED, failed.getStatus());
        assertEquals(AIGenerationJobService.ERROR_NO_OUTPUT, failed.getErrorCode());
        assertEquals(0, failed.getSuccessCount(), "a run that wrote nothing reports nothing");
        assertEquals(1, failed.getFailureCount(), "the job's single target produced no entity");
        assertEquals("no blocks structured", failed.getSafeMessage());
    }

    @Test
    void missingTargetForQuestionGenerationFailsWithANamedCode() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_QUESTION_GENERATION);

        workerA.dispatch(job.getId());

        AIGenerationJob failed = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_FAILED, failed.getStatus());
        assertEquals(AIGenerationJobService.ERROR_NO_OUTPUT, failed.getErrorCode());
        Mockito.verifyNoInteractions(questionGeneration);
    }

    // ==================== F-22(a): no accepted type without a handler ====================

    @Test
    void examDiagnosisNarrativeHasNoHandlerSoItIsNotAnAcceptedType() {
        // The invariant that keeps the controller allow-list and the worker
        // switch from drifting apart again: the accepted set IS this set.
        assertEquals(3, AIGenerationJobWorker.SUPPORTED_JOB_TYPES.size());
        assertTrue(AIGenerationJobWorker.SUPPORTED_JOB_TYPES.stream()
                        .noneMatch(type -> type.contains("narrative")),
                "an accepted job type with no worker branch can only ever fail");
        assertTrue(AIGenerationJobWorker.SUPPORTED_JOB_TYPES.containsAll(List.of(
                AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE,
                AIGenerationJobWorker.JOB_TYPE_KNOWLEDGE_EXTRACTION,
                AIGenerationJobWorker.JOB_TYPE_QUESTION_GENERATION)));
    }

    @Test
    void aLegacyRowOfAWithdrawnTypeFailsWithAStableCodeNotAnInternalError() {
        // Rows created while the type was still accepted must still land
        // somewhere honest instead of the opaque IllegalStateException the old
        // default branch threw.
        AIGenerationJob job = seed("exam-diagnosis-narrative");

        workerA.dispatch(job.getId());

        AIGenerationJob failed = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_FAILED, failed.getStatus());
        assertEquals(AIGenerationJobService.ERROR_UNSUPPORTED_TYPE, failed.getErrorCode());
        assertEquals(0, failed.getSuccessCount());
        assertEquals(1, failed.getFailureCount());
        Mockito.verifyNoInteractions(contentStructure, knowledgeExtraction, questionGeneration);
    }

    @Test
    void handlerBlowUpIsSanitizedIntoTheJobRow() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_KNOWLEDGE_EXTRACTION);
        Mockito.when(sourceDiagnosis.extractSourceText(USER, SPACE, 5L)).thenReturn("text");
        Mockito.when(knowledgeExtraction.extract(USER, SPACE, "text"))
                .thenThrow(new IllegalStateException("HTTP 401 from provider, api key sk-secret"));

        workerA.dispatch(job.getId());

        AIGenerationJob failed = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_FAILED, failed.getStatus());
        assertEquals(AIGenerationJobService.ERROR_PROCESSING, failed.getErrorCode());
        assertEquals("AI provider configuration error", failed.getSafeMessage(),
                "raw provider credential detail must never reach the row");
    }

    // ==================== F-29: no duplicate generation ====================

    @Test
    void aJobUnderAnotherWorkersLiveLeaseIsNeverStartedTwice() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE);
        String tokenB = service.claim(job.getId(), workerB.workerId());
        assertNotNull(tokenB);
        Mockito.when(contentStructure.structureBlocks(USER, SPACE, 5L)).thenReturn(2);

        // Worker A is handed the same row by a duplicate dispatch or a drain.
        workerA.dispatch(job.getId());

        assertEquals(AIGenerationJobService.STATUS_PROCESSING, table.row(job.getId()).getStatus());
        assertEquals(tokenB, table.row(job.getId()).getClaimToken(), "A must not steal the lease");
        // The second worker generates nothing, so there is no second draft set.
        Mockito.verifyNoInteractions(contentStructure);
    }

    @Test
    void anAttemptThatLostItsLeaseStopsBeforeCallingTheProvider() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE);
        String tokenA = service.claim(job.getId(), workerA.workerId());
        assertNotNull(tokenA);
        // A stalls, recovery reclaims, B becomes the holder.
        table.row(job.getId()).setClaimedAt(LocalDateTime.now().minusMinutes(31));
        service.recoverStaleJobs();
        assertNotNull(service.claim(job.getId(), workerB.workerId()));

        assertThrows(AiJobFailureException.LeaseLostException.class,
                () -> workerA.execute(table.row(job.getId()), tokenA));

        // The lease renewal in front of the expensive step is the ownership
        // probe: a stale attempt cannot emit a second draft result set at all.
        Mockito.verifyNoInteractions(contentStructure);
    }

    @Test
    void aStepThatLosesItsLeaseHalfwayNeverReachesTheProvider() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_KNOWLEDGE_EXTRACTION);
        String tokenA = service.claim(job.getId(), workerA.workerId());
        // The source text assembly is slow: by the time it returns, recovery has
        // handed the job to B. The provider call must not start.
        Mockito.when(sourceDiagnosis.extractSourceText(USER, SPACE, 5L)).thenAnswer(invocation -> {
            table.row(job.getId()).setClaimedAt(LocalDateTime.now().minusMinutes(31));
            service.recoverStaleJobs();
            assertNotNull(service.claim(job.getId(), workerB.workerId()));
            return "chapter one";
        });

        assertThrows(AiJobFailureException.LeaseLostException.class,
                () -> workerA.execute(table.row(job.getId()), tokenA));

        // No extraction ran, so no second set of draft knowledge points exists.
        Mockito.verifyNoInteractions(knowledgeExtraction);
    }

    @Test
    void anAttemptWhoseLeaseWasStolenMidRunCannotReportAResult() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE);
        final boolean[] stolen = {false};
        final int[] handlerCalls = {0};
        // A is already inside the provider call when its lease ages out; B then
        // takes the job over and completes it, and A's own call returns a
        // different number afterwards. A's terminal write must be refused.
        Mockito.when(contentStructure.structureBlocks(USER, SPACE, 5L))
                .thenAnswer(invocation -> {
                    handlerCalls[0]++;
                    if (!stolen[0]) {
                        stolen[0] = true;
                        table.row(job.getId()).setClaimedAt(LocalDateTime.now().minusMinutes(31));
                        service.recoverStaleJobs();
                        // B goes through the normal path: dispatch -> claim -> run.
                        workerB.dispatch(job.getId());
                        return 9;
                    }
                    return 5;
                });

        workerA.dispatch(job.getId());

        AIGenerationJob done = table.row(job.getId());
        assertEquals(2, handlerCalls[0],
                "one provider call per attempt: the call A already had in flight is the residual "
                        + "window a lease cannot close, the refused write below is what it does close");
        assertEquals(AIGenerationJobService.STATUS_COMPLETED, done.getStatus());
        assertEquals(5, done.getSuccessCount(),
                "the abandoned attempt must not overwrite the count of the run holding the lease");
        assertEquals(0, done.getFailureCount());
        assertEquals(workerB.workerId(), done.getClaimedBy());
    }

    @Test
    void progressOfALostAttemptIsRefusedToo() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE);
        String tokenA = service.claim(job.getId(), workerA.workerId());
        table.row(job.getId()).setClaimToken("the-new-holder");

        assertFalse(service.reportProgress(job.getId(), workerA.workerId(), tokenA, 50),
                "display state is lease-bound as well, so a dead attempt stops updating the UI");
    }

    // ==================== A-06: dispatch contract ====================

    @Test
    void aRefusedSubmissionLeavesTheJobHonestlyPending() {
        AIGenerationJob job = seed(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE);
        AIGenerationJobWorker saturated = newWorker(new RejectingTaskExecutor());

        saturated.dispatch(job.getId());

        AIGenerationJob still = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_PENDING, still.getStatus(),
                "no free slot is PENDING, never a PROCESSING no thread will move");
        assertNull(still.getClaimedBy());
        assertNull(still.getClaimToken());
        Mockito.verifyNoInteractions(contentStructure);
    }

    @Test
    void dispatchIsDeferredUntilTheCreateCommits() {
        // Submitting inside the transaction raced the insert: the worker could
        // not see the row yet, so the job stayed PENDING with nobody scheduled.
        List<Long> handed = new ArrayList<>();
        AIGenerationJobService wired =
                new AIGenerationJobService(table.mapper(), handed::add, 1_800_000L, 300_000L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            AIGenerationJob created = wired.create(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE,
                    USER, SPACE, 5L, null, null);
            assertTrue(handed.isEmpty(), "not dispatched before commit");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCommit());

            assertEquals(List.of(created.getId()), handed);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recoveryRequeuesAndRedispatchesInOneTick() {
        AIGenerationJob abandoned = seed(AIGenerationJobWorker.JOB_TYPE_CONTENT_STRUCTURE);
        service.claim(abandoned.getId(), workerA.workerId());
        table.row(abandoned.getId()).setClaimedAt(LocalDateTime.now().minusMinutes(31));
        List<Long> handed = new ArrayList<>();
        AIGenerationJobService ticking =
                new AIGenerationJobService(table.mapper(), handed::add, 1_800_000L, 300_000L);

        int touched = ticking.recoverStaleJobs();

        assertEquals(2, touched, "one job requeued plus the same job dispatched again");
        assertEquals(List.of(abandoned.getId()), handed,
                "a reclaimed job is handed over again on the same tick");
        assertNull(table.row(abandoned.getId()).getClaimToken());
    }

    // ==================== helpers ====================

    /** Runs the task on the calling thread so lease effects are assertable. */
    private static final class InlineTaskExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    /** Stands in for a full pool: the rejection path is what is under test. */
    private static final class RejectingTaskExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new RejectedExecutionException("pool exhausted");
        }
    }

    // ---- seeding ----

    private AIGenerationJob seed(String jobType) {
        AIGenerationJob job = new AIGenerationJob();
        job.setJobType(jobType);
        job.setStatus(AIGenerationJobService.STATUS_PENDING);
        job.setProgress(0);
        job.setRequester(USER);
        job.setSpaceId(SPACE);
        job.setSourceId(5L);
        job.setSuccessCount(0);
        job.setFailureCount(0);
        job.setRetryCount(0);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return table.seed(job);
    }
}
