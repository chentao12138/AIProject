package com.aistudy.server.ai.job;

import com.aistudy.server.ai.controller.AiGenerationJobController;
import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.service.AIGenerationJobService;
import com.aistudy.server.ai.service.AIGenerationJobWorker;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.space.entity.LearningSpace;
import com.aistudy.server.space.service.LearningSpaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The HTTP boundary of the AI generation job API: the jobType allow-list is the
 * worker's own supported set (F-22), and every read is bound to the path space
 * so a job of another space is a 404 with the same text as a missing one (F-21).
 */
class AiGenerationJobControllerTest {

    private static final String USER = "user-1";
    private static final long SPACE_A = 10L;
    private static final long SPACE_B = 20L;

    private AIGenerationJobService service;
    private LearningSpaceService learningSpaceService;
    private AiGenerationJobController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(AIGenerationJobService.class);
        learningSpaceService = Mockito.mock(LearningSpaceService.class);
        controller = new AiGenerationJobController(service, learningSpaceService);
        authentication = new UsernamePasswordAuthenticationToken(USER, null, List.of());
        when(learningSpaceService.getMine(USER, SPACE_A)).thenReturn(new LearningSpace());
        when(learningSpaceService.getMine(USER, SPACE_B)).thenReturn(new LearningSpace());
    }

    @Test
    void onlyJobTypesTheWorkerCanActuallyRunAreAccepted() {
        for (String supported : AIGenerationJobWorker.SUPPORTED_JOB_TYPES) {
            when(service.create(supported, USER, SPACE_A, 1L, null, null)).thenReturn(job(1L, SPACE_A));
            controller.create(SPACE_A, request(supported), authentication);
        }

        ApiException error = assertThrows(ApiException.class, () -> controller.create(SPACE_A,
                request("exam-diagnosis-narrative"), authentication));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertEquals("VALIDATION_ERROR", error.code());
        // The name of the rejected type plus the accepted set, so the client can
        // fix its call instead of guessing.
        assertEquals(true, error.detail().contains("exam-diagnosis-narrative"));
        assertEquals(true, error.detail().contains("question-generation"));
        verify(service, never()).create(eq("exam-diagnosis-narrative"), any(), anyLong(),
                any(), any(), any());
    }

    @Test
    void createWritesThePathSpaceAsTheVisibilityScope() {
        when(service.create("content-structure", USER, SPACE_B, 3L, 4L, 5L))
                .thenReturn(job(9L, SPACE_B));

        AIGenerationJob created = controller.create(SPACE_B,
                new AiGenerationJobController.CreateGenerationJobRequest(
                        "content-structure", 3L, 4L, 5L, null), authentication);

        assertEquals(SPACE_B, created.getSpaceId());
        verify(service).create("content-structure", USER, SPACE_B, 3L, 4L, 5L);
    }

    @Test
    void unknownSpaceIs404BeforeAnyJobLookup() {
        when(learningSpaceService.getMine(USER, 999L)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.get(999L, 1L, authentication));
        assertEquals(HttpStatus.NOT_FOUND, error.status());
        verify(service, never()).getMine(any(), any(), any());
    }

    @Test
    void getOfACrossSpaceJobIs404WithTheSameTextAsAMissingOne() {
        // The service returns null for "another space" and "does not exist"
        // alike; the controller must not turn one of them into a 403.
        when(service.getMine(USER, SPACE_A, 77L)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.get(SPACE_A, 77L, authentication));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("AIGenerationJob not found", error.detail());
        assertEquals("NOT_FOUND", error.code());
    }

    @Test
    void listIsScopedToThePathSpaceAndSizeIsClamped() {
        when(service.listMine(USER, SPACE_A, 2, 100)).thenReturn(
                new AIGenerationJobService.JobPage(List.of(job(1L, SPACE_A)), 2, 100, 201L, 3));

        AiGenerationJobController.GenerationJobPageResponse response =
                controller.list(SPACE_A, 2, 5_000, authentication);

        assertEquals(201L, response.totalElements());
        assertEquals(3, response.totalPages());
        assertEquals(100, response.size(), "size is clamped, not honoured verbatim");
        assertTrueAllInSpace(response.content(), SPACE_A);
        verify(service).listMine(USER, SPACE_A, 2, 100);
    }

    @Test
    void retryPassesThePathSpaceSoTheServiceCanRefuseACrossSpaceJob() {
        when(service.retry(USER, SPACE_A, 5L)).thenReturn(job(5L, SPACE_A));

        controller.retry(SPACE_A, 5L, authentication);

        verify(service).retry(USER, SPACE_A, 5L);
    }

    @Test
    void retryOfACrossSpaceJobPropagatesThe404() {
        when(service.retry(USER, SPACE_A, 5L)).thenThrow(new ApiException(HttpStatus.NOT_FOUND,
                "NOT_FOUND", "AIGenerationJob not found"));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.retry(SPACE_A, 5L, authentication));
        assertEquals(HttpStatus.NOT_FOUND, error.status());
    }

    private static void assertTrueAllInSpace(List<AIGenerationJob> jobs, long spaceId) {
        assertEquals(1, jobs.size());
        assertEquals(spaceId, jobs.get(0).getSpaceId());
    }

    private static AiGenerationJobController.CreateGenerationJobRequest request(String jobType) {
        return new AiGenerationJobController.CreateGenerationJobRequest(jobType, 1L, null, null, null);
    }

    private static AIGenerationJob job(Long id, long spaceId) {
        AIGenerationJob job = new AIGenerationJob();
        job.setId(id);
        job.setSpaceId(spaceId);
        job.setRequester(USER);
        job.setStatus(AIGenerationJobService.STATUS_PENDING);
        job.setProgress(0);
        job.setSuccessCount(0);
        job.setFailureCount(0);
        job.setRetryCount(0);
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }
}
