package com.aistudy.server.ai.job;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.service.AIGenerationJobService;
import com.aistudy.server.common.problem.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-29 — the lease, not the status, decides who may write.
 *
 * <p>Covers the three properties that stop a requeued job from being generated
 * twice: a claim is atomic and mints a per-attempt token, a beating worker is
 * never requeued, and every write after the claim is refused once the token is
 * no longer the live one.
 */
class AiGenerationJobLeaseTest {

    private static final String USER = "user-1";
    private static final long SPACE = 10L;
    private static final String WORKER_A = "ai-generation-worker-aaaaaaa";
    private static final String WORKER_B = "ai-generation-worker-bbbbbbb";

    private AiGenerationJobTableFake table;
    private AIGenerationJobService service;
    private final List<Long> dispatched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        table = new AiGenerationJobTableFake();
        service = newService(1_800_000L, 300_000L);
    }

    private AIGenerationJobService newService(long staleLeaseMs, long heartbeatMs) {
        return new AIGenerationJobService(table.mapper(), dispatched::add, staleLeaseMs, heartbeatMs);
    }

    @Test
    void claimIsAtomicAndMintsAPerAttemptToken() {
        AIGenerationJob job = seed(AIGenerationJobService.STATUS_PENDING, null, null);

        String token = service.claim(job.getId(), WORKER_A);

        assertNotNull(token);
        assertEquals(AIGenerationJobService.STATUS_PROCESSING, table.row(job.getId()).getStatus());
        assertEquals(WORKER_A, table.row(job.getId()).getClaimedBy());
        assertEquals(token, table.row(job.getId()).getClaimToken());

        // Same row, second worker: the UPDATE predicate can only be satisfied once.
        assertNull(service.claim(job.getId(), WORKER_B),
                "a live PROCESSING lease is not claimable, or two workers generate the same job");
        assertEquals(token, table.row(job.getId()).getClaimToken(),
                "the loser must not overwrite the winner's token");
    }

    @Test
    void twoClaimsByTheSameWorkerStillGetDistinctTokens() {
        AIGenerationJob job = seed(AIGenerationJobService.STATUS_PENDING, null, null);
        String first = service.claim(job.getId(), WORKER_A);
        // Recovery clears the lease; the same process-wide worker name returns.
        table.row(job.getId()).setStatus(AIGenerationJobService.STATUS_PENDING);
        table.row(job.getId()).setClaimedBy(null);
        table.row(job.getId()).setClaimedAt(null);
        table.row(job.getId()).setClaimToken(null);

        String second = service.claim(job.getId(), WORKER_A);

        assertNotNull(second);
        assertFalse(second.equals(first),
                "a worker id is process-wide and survives a requeue, so the name alone "
                        + "cannot tell this attempt from the abandoned one");
    }

    @Test
    void onlyTheTokenHolderCanRenewTheLease() {
        AIGenerationJob job = seed(AIGenerationJobService.STATUS_PENDING, null, null);
        String token = service.claim(job.getId(), WORKER_A);

        assertTrue(service.renewLease(job.getId(), WORKER_A, token));
        assertFalse(service.renewLease(job.getId(), WORKER_A, "not-my-token"));
        assertFalse(service.renewLease(job.getId(), WORKER_B, token),
                "same token, different holder name must not renew");
    }

    @Test
    void renewalPushesTheLeaseOutOfTheRecoveryWindow() {
        AIGenerationJob job = seed(AIGenerationJobService.STATUS_PENDING, null, null);
        String token = service.claim(job.getId(), WORKER_A);
        // Simulate a run that started 29 minutes ago and has been beating.
        table.row(job.getId()).setClaimedAt(LocalDateTime.now().minusMinutes(29));

        LocalDateTime beforeBeat = table.row(job.getId()).getClaimedAt();
        assertTrue(service.renewLease(job.getId(), WORKER_A, token));

        assertTrue(table.row(job.getId()).getClaimedAt().isAfter(beforeBeat),
                "the heartbeat is what a long AI call leaves behind; without it the 30m "
                        + "sweep would requeue a job that is still running");
        assertEquals(0, service.recoverStaleJobs(), "a beating job is not stale");
        assertEquals(AIGenerationJobService.STATUS_PROCESSING, table.row(job.getId()).getStatus());
    }

    @Test
    void recoveryRequeuesOnlyTheSilentLeaseAndClearsItsToken() {
        AIGenerationJob silent = seed(AIGenerationJobService.STATUS_PROCESSING, WORKER_A, "token-silent");
        table.row(silent.getId()).setClaimedAt(LocalDateTime.now().minusMinutes(31));
        AIGenerationJob beating = seed(AIGenerationJobService.STATUS_PROCESSING, WORKER_A, "token-beating");
        table.row(beating.getId()).setClaimedAt(LocalDateTime.now().minusMinutes(4));

        service.recoverStaleJobs();

        assertEquals(AIGenerationJobService.STATUS_PENDING, table.row(silent.getId()).getStatus());
        assertNull(table.row(silent.getId()).getClaimToken(),
                "the abandoned attempt's token must stop matching, or it can still finalize");
        assertNull(table.row(silent.getId()).getClaimedBy());
        assertEquals(AIGenerationJobService.STATUS_PROCESSING, table.row(beating.getId()).getStatus(),
                "recovery must not touch a job that is inside its lease");
    }

    @Test
    void abandonedAttemptCannotFinalizeAJobRecoveryAlreadyHandedOver() {
        AIGenerationJob job = seed(AIGenerationJobService.STATUS_PENDING, null, null);
        String staleToken = service.claim(job.getId(), WORKER_A);

        // Recovery requeues the silent lease, worker B claims and finishes.
        table.row(job.getId()).setClaimedAt(LocalDateTime.now().minusMinutes(31));
        service.recoverStaleJobs();
        String liveToken = service.claim(job.getId(), WORKER_B);
        assertNotNull(liveToken);
        service.finish(job.getId(), liveToken, 3, 0);

        // The first attempt wakes up late and tries to write its own result.
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.finish(job.getId(), staleToken, 9, 9));
        assertEquals(HttpStatus.CONFLICT, conflict.status());

        AIGenerationJob after = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_COMPLETED, after.getStatus());
        assertEquals(3, after.getSuccessCount(), "the abandoned attempt must not overwrite the real count");
        assertFalse(table.calls().contains("markTerminalFailure"));

        // The same holds for the failure path: it returns false instead of
        // overwriting, so a stale worker cannot flip a COMPLETED job to FAILED.
        assertFalse(service.fail(job.getId(), staleToken,
                AIGenerationJobService.ERROR_PROCESSING, "boom", 0, 1));
        assertEquals(AIGenerationJobService.STATUS_COMPLETED, table.row(job.getId()).getStatus());
    }

    @Test
    void terminalWritesAreConditionalOnStatusAsWellAsToken() {
        AIGenerationJob job = seed(AIGenerationJobService.STATUS_PENDING, null, null);
        String token = service.claim(job.getId(), WORKER_A);

        service.finish(job.getId(), token, 5, 0);

        assertEquals(AIGenerationJobService.STATUS_COMPLETED, table.row(job.getId()).getStatus());
        assertEquals(100, table.row(job.getId()).getProgress());
        // A second finalize of the same attempt is refused by the status guard.
        assertThrows(ApiException.class, () -> service.finish(job.getId(), token, 5, 0));
    }

    @Test
    void failureWritePersistsStatusAndDiagnosticsInOneStatement() {
        AIGenerationJob job = seed(AIGenerationJobService.STATUS_PENDING, null, null);
        String token = service.claim(job.getId(), WORKER_A);

        assertTrue(service.fail(job.getId(), token, AIGenerationJobService.ERROR_NO_OUTPUT,
                "no blocks structured", 0, 1));

        AIGenerationJob failed = table.row(job.getId());
        assertEquals(AIGenerationJobService.STATUS_FAILED, failed.getStatus());
        assertEquals(AIGenerationJobService.ERROR_NO_OUTPUT, failed.getErrorCode());
        assertEquals("no blocks structured", failed.getSafeMessage());
        assertEquals(0, failed.getSuccessCount());
        assertEquals(1, failed.getFailureCount());
        assertNotNull(failed.getFinishedAt());
    }

    @Test
    void progressFromALostHolderIsRefused() {
        AIGenerationJob job = seed(AIGenerationJobService.STATUS_PENDING, null, null);
        String token = service.claim(job.getId(), WORKER_A);
        table.row(job.getId()).setClaimToken("someone-else");

        assertFalse(service.reportProgress(job.getId(), WORKER_A, token, 50));
    }

    @Test
    void recoveryDispatchesPendingRowsSoARefusedSubmissionIsRetried() {
        AIGenerationJob pending = seed(AIGenerationJobService.STATUS_PENDING, null, null);

        int touched = service.recoverStaleJobs();

        assertEquals(1, touched);
        assertEquals(List.of(pending.getId()), dispatched,
                "a job the pool refused stays PENDING and is handed over again by the tick, "
                        + "which is what makes PENDING an honest state");
    }

    @Test
    void heartbeatIsAlwaysShorterThanTheLeaseItIsSupposedToStayInside() {
        // Misconfigured pair: a beat that never lands before the sweep would
        // requeue every long job and re-introduce the double run.
        AIGenerationJobService clampService = newService(Duration.ofMinutes(10).toMillis(),
                Duration.ofHours(1).toMillis());

        assertTrue(clampService.heartbeatInterval().compareTo(clampService.staleLease()) < 0);
        assertEquals(Duration.ofMinutes(10).toMillis(), clampService.staleLease().toMillis(),
                "the lease itself is honoured as configured");
    }

    @Test
    void leaseFloorKeepsRecoveryFromRunningAwayWithABrokenConfig() {
        AIGenerationJobService tiny = newService(1L, 1L);
        assertEquals(1_000L, tiny.staleLease().toMillis());
        assertTrue(tiny.heartbeatInterval().toMillis() >= 200L);
    }

    @Test
    void sanitizeNeverLeaksProviderCredentials() {
        assertEquals("AI provider configuration error",
                AIGenerationJobService.sanitizeSafeMessage(
                        new IllegalStateException("missing api key sk-abcdef")));
        assertEquals("AI provider configuration error",
                AIGenerationJobService.sanitizeSafeMessage(
                        new IllegalStateException("Authorization: Bearer xyz")));
        assertEquals("AI generation failed",
                AIGenerationJobService.sanitizeSafeMessage(new IllegalStateException("  ")));
        assertEquals("AI generation failed", AIGenerationJobService.sanitizeSafeMessage(null));
        assertTrue(AIGenerationJobService.sanitizeSafeMessage(
                new IllegalStateException("x".repeat(400))).length() <= 200);
    }

    private AIGenerationJob seed(String status, String claimedBy, String claimToken) {
        AIGenerationJob job = new AIGenerationJob();
        job.setJobType("content-structure");
        job.setStatus(status);
        job.setProgress(0);
        job.setRequester(USER);
        job.setSpaceId(SPACE);
        job.setSourceId(5L);
        job.setSuccessCount(0);
        job.setFailureCount(0);
        job.setRetryCount(0);
        job.setClaimedBy(claimedBy);
        job.setClaimedAt(claimedBy == null ? null : LocalDateTime.now());
        job.setClaimToken(claimToken);
        job.setCreatedAt(LocalDateTime.now());
        return table.seed(job);
    }
}
