package com.aistudy.server.ai.job;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.service.AIGenerationJobService;
import com.aistudy.server.common.problem.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-21 — the space path segment must scope every owner read of an AI generation
 * job. A job is visible when it matches BOTH {@code space_id} and the JWT
 * subject, and a mismatch is answered like a missing row rather than a
 * forbidden one.
 */
class AiGenerationJobSpaceScopeTest {

    private static final String USER = "user-1";
    private static final long SPACE_A = 10L;
    private static final long SPACE_B = 20L;

    private AiGenerationJobTableFake table;
    private AIGenerationJobService service;
    private final List<Long> dispatched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        table = new AiGenerationJobTableFake();
        service = new AIGenerationJobService(table.mapper(), dispatched::add, 1_800_000L, 300_000L);
    }

    @Test
    void listReturnsOnlyThePathSpaceAndCountsOnlyWhatItReturned() {
        seed(SPACE_A, USER, "content-structure");
        seed(SPACE_A, USER, "knowledge-extraction");
        seed(SPACE_B, USER, "question-generation");
        seed(SPACE_A, "other-user", "content-structure");

        AIGenerationJobService.JobPage page = service.listMine(USER, SPACE_A, 0, 20);

        assertEquals(2, page.totalElements(), "total must not count jobs of another space");
        assertEquals(2, page.content().size());
        assertTrue(page.content().stream().allMatch(job -> SPACE_A == job.getSpaceId()));
        assertTrue(page.content().stream().allMatch(job -> USER.equals(job.getRequester())));
        assertEquals(1, page.totalPages());
    }

    @Test
    void countIsScopedPerSpaceSoTheSameUserSeesDifferentTotals() {
        seed(SPACE_A, USER, "content-structure");
        seed(SPACE_B, USER, "question-generation");
        seed(SPACE_B, USER, "knowledge-extraction");

        assertEquals(1, service.listMine(USER, SPACE_A, 0, 20).totalElements());
        assertEquals(2, service.listMine(USER, SPACE_B, 0, 20).totalElements());
    }

    @Test
    void pagingIsScopedSoOffsetNeverWalksIntoAnotherSpace() {
        seed(SPACE_A, USER, "content-structure");
        seed(SPACE_B, USER, "question-generation");
        seed(SPACE_A, USER, "knowledge-extraction");

        AIGenerationJobService.JobPage secondPage = service.listMine(USER, SPACE_A, 1, 2);

        assertEquals(2, secondPage.totalElements(), "only the two Space A jobs are counted");
        assertTrue(secondPage.content().isEmpty(),
                "page 2 of a 2-element space-scoped list is empty, not Space B's row");
    }

    @Test
    void getMineIsInvisibleAcrossSpaces() {
        AIGenerationJob inB = seed(SPACE_B, USER, "question-generation");

        assertNull(service.getMine(USER, SPACE_A, inB.getId()));
        assertEquals(inB.getId(), service.getMine(USER, SPACE_B, inB.getId()).getId());
    }

    @Test
    void retryOfACrossSpaceJobIs404Not403AndNeverMutatesTheRow() {
        AIGenerationJob inB = seed(SPACE_B, USER, "content-structure");
        inB.setStatus(AIGenerationJobService.STATUS_FAILED);

        ApiException error = assertThrows(ApiException.class, () -> service.retry(USER, SPACE_A, inB.getId()));

        // A 403 would tell the caller the id is real and lives in some other
        // space; that is the existence leak this task is about.
        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("AIGenerationJob not found", error.detail());
        assertEquals(AIGenerationJobService.STATUS_FAILED, table.row(inB.getId()).getStatus(),
                "an out-of-scope retry must not reset anything");
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void retryOfSomeoneElsesJobInMySpaceIsAlso404() {
        AIGenerationJob foreigner = seed(SPACE_A, "other-user", "content-structure");
        foreigner.setStatus(AIGenerationJobService.STATUS_FAILED);

        ApiException error = assertThrows(ApiException.class, () -> service.retry(USER, SPACE_A, foreigner.getId()));
        assertEquals(HttpStatus.NOT_FOUND, error.status());
    }

    @Test
    void retryResetsLeaseAndCountersSoTheNextAttemptStartsClean() {
        AIGenerationJob failed = seed(SPACE_A, USER, "knowledge-extraction");
        failed.setStatus(AIGenerationJobService.STATUS_FAILED);
        failed.setSuccessCount(7);
        failed.setFailureCount(1);
        failed.setClaimedBy("stale-worker");
        failed.setClaimedAt(LocalDateTime.now().minusMinutes(41));
        failed.setClaimToken("old-token");

        AIGenerationJob retried = service.retry(USER, SPACE_A, failed.getId());

        assertEquals(AIGenerationJobService.STATUS_PENDING, retried.getStatus());
        assertEquals(0, retried.getSuccessCount(), "a fresh attempt must not inherit the last run's counts");
        assertEquals(0, retried.getFailureCount());
        assertNull(retried.getClaimToken(), "the old token must not survive into the new attempt");
        assertNull(retried.getClaimedBy());
        assertEquals(1, retried.getRetryCount());
        assertEquals(List.of(failed.getId()), dispatched, "the retried job is handed to the pool");
    }

    @Test
    void retryOfANonFailedJobIsConflict() {
        AIGenerationJob running = seed(SPACE_A, USER, "content-structure");
        running.setStatus(AIGenerationJobService.STATUS_PROCESSING);

        ApiException error = assertThrows(ApiException.class, () -> service.retry(USER, SPACE_A, running.getId()));
        assertEquals(HttpStatus.CONFLICT, error.status());
    }

    @Test
    void createIsScopedToThePathSpaceAndStaysPendingUntilCommittedWorkRuns() {
        AIGenerationJob created = service.create("content-structure", USER, SPACE_A, 5L, null, 6L);

        assertEquals(SPACE_A, created.getSpaceId());
        assertEquals(USER, created.getRequester());
        assertEquals(AIGenerationJobService.STATUS_PENDING, created.getStatus());
        assertEquals(0, created.getSuccessCount());
        assertEquals(0, created.getFailureCount());
        assertNull(created.getClaimToken());
        assertEquals(List.of(created.getId()), dispatched);
    }

    private int seedCounter;

    private AIGenerationJob seed(long spaceId, String requester, String jobType) {
        AIGenerationJob job = new AIGenerationJob();
        job.setJobType(jobType);
        job.setStatus(AIGenerationJobService.STATUS_PENDING);
        job.setProgress(0);
        job.setRequester(requester);
        job.setSpaceId(spaceId);
        job.setSuccessCount(0);
        job.setFailureCount(0);
        job.setRetryCount(0);
        // Distinct, increasing: created_at DESC ordering must not depend on ties.
        job.setCreatedAt(LocalDateTime.now().minusMinutes(20L - seedCounter++));
        return table.seed(job);
    }
}
