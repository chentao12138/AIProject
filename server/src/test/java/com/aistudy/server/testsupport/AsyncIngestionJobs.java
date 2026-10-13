package com.aistudy.server.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Waits for an ingestion job to reach a state, instead of assuming the
 * create response already carries it.
 *
 * <p>Ingestion is asynchronous by contract (architecture.md §6.2):
 * {@code IngestionJobService.submit()} dispatches to the bounded worker pool
 * from an {@code afterCommit()} hook, so the HTTP create response only ever
 * reports the job's initial {@code QUEUED} state — the pipeline runs on
 * another thread and finishes some milliseconds later, or not at all if it
 * is still waiting for a free worker. Tests that assert a terminal status on
 * the create body are therefore racing their own worker thread; they have to
 * poll the job read endpoint the same way a client does.
 *
 * <h3>Vocabulary</h3>
 *
 * <p>The only status vocabulary is
 * {@code IngestionJobService.IngestionStatus} (mirrored by the
 * {@code IngestionJob} entity javadoc): {@code QUEUED → IMPORTING →
 * EXTRACTING → STRUCTURING → {NEEDS_REVIEW | PARTIAL_FAILED | FAILED}}.
 * {@code PENDING}/{@code RUNNING}/{@code SUCCEEDED} are the dead vocabulary
 * architecture.md §6.2 explicitly retired, so {@code NEEDS_REVIEW} is what a
 * successful ingestion terminates at and {@code REVIEW} is its stage.
 *
 * <h3>Failure reporting</h3>
 *
 * <p>A timeout does not silently mean "too slow": every terminal status that
 * differs from the expected one aborts the wait immediately and reports the
 * observed {@code status/stage/errorCode/errorMessage}, so a pipeline that
 * reaches the <em>wrong</em> terminal state fails fast with the reason
 * instead of hiding behind a 10-second deadline.
 */
public final class AsyncIngestionJobs {

    /** The collection endpoint, kept here so every caller polls the same API. */
    public static final String JOBS =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs";

    /** The single-job read endpoint. */
    public static final String JOB_DETAIL =
            "/api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}";

    /** How long a job gets to reach the awaited state. */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Pause between two reads; short enough to keep the suite quick. */
    public static final Duration INTERVAL = Duration.ofMillis(75);

    /** States no worker leaves a job in — see the entity lifecycle javadoc. */
    private static final java.util.Set<String> TERMINAL = java.util.Set.of(
            "NEEDS_REVIEW", "PUBLISHED", "PARTIAL_FAILED", "FAILED");

    /** The status {@code IngestionJobService.create()} writes. */
    private static final String QUEUED = "QUEUED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AsyncIngestionJobs() {
    }

    /**
     * Creates a job and returns the body of the read that confirms
     * {@code expectedStatus}.
     *
     * <p>The 201 response itself is asserted. {@code QUEUED} is verified
     * against that create response and not polled: it is the initial state,
     * and the afterCommit dispatch can move the job past it at any moment, so
     * the create body is the only race-free place to observe it.
     *
     * @return the job body carrying {@code expectedStatus} — always the
     *         terminal body for a terminal expectation, so callers can keep
     *         asserting {@code errorCode}, {@code assetId} and friends
     */
    public static String createAndAwait(MockMvc mockMvc, String token, Long spaceId,
                                        Long sourceId, Long assetId,
                                        String expectedStatus) throws Exception {
        String created = create(mockMvc, token, spaceId, sourceId, assetId);
        if (QUEUED.equals(expectedStatus)) {
            assertEquals(QUEUED, statusOf(created),
                    "create response must carry the job's initial QUEUED state");
            return created;
        }
        return awaitStatus(mockMvc, token, spaceId, jobIdOf(created), expectedStatus);
    }

    /**
     * Same wait as {@link #createAndAwait}, but hands back the job id so a
     * test can keep asserting with MockMvc's {@code jsonPath} matchers on its
     * own read of the job.
     */
    public static long createAndAwaitJobId(MockMvc mockMvc, String token, Long spaceId,
                                           Long sourceId, Long assetId,
                                           String expectedStatus) throws Exception {
        String created = create(mockMvc, token, spaceId, sourceId, assetId);
        long jobId = jobIdOf(created);
        if (!QUEUED.equals(expectedStatus)) {
            awaitStatus(mockMvc, token, spaceId, jobId, expectedStatus);
        }
        return jobId;
    }

    /** Creates a job, asserts 201, and returns the create response body. */
    public static String create(MockMvc mockMvc, String token, Long spaceId,
                                Long sourceId, Long assetId) throws Exception {        return mockMvc.perform(post(JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Polls {@code GET /api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}} until
     * the job reports {@code expectedStatus}.
     *
     * @return the job body that matched
     */
    public static String awaitStatus(MockMvc mockMvc, String token, Long spaceId,
                                     Long jobId, String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        String body = null;
        do {
            body = read(mockMvc, token, spaceId, jobId);
            String observed = statusOf(body);
            if (expectedStatus.equals(observed)) {
                return body;
            }
            if (TERMINAL.contains(observed)) {
                throw failure("ingestion job " + jobId + " reached terminal status "
                        + describe(body) + " but the test awaits " + expectedStatus
                        + " (waited " + TIMEOUT.toMillis() + "ms at most, polled from QUEUED);"
                        + " body=" + body);
            }
            sleepBetweenPolls();
        } while (System.nanoTime() < deadline);
        throw failure("ingestion job " + jobId + " did not reach " + expectedStatus
                + " within " + TIMEOUT.toSeconds() + "s; last observed" + describe(body)
                + "; body=" + body);
    }

    /** Reads one job and asserts 200, returning the raw body. */
    public static String read(MockMvc mockMvc, String token, Long spaceId,
                              Long jobId) throws Exception {
        MvcResult result = mockMvc.perform(get(JOB_DETAIL, spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "job read must succeed while waiting for job " + jobId
                        + "; body=" + result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString();
    }

    /** The {@code id} of a create response, as a long. */
    public static long jobIdOf(String body) {
        JsonNode id = node(body).path("id");
        if (!id.canConvertToLong()) {
            throw failure("create response carries no job id: " + body);
        }
        return id.asLong();
    }

    /** The {@code status} field of a job body. */
    public static String statusOf(String body) {
        return node(body).path("status").asText(null);
    }

    /** A compact status/stage/error summary for assertion messages. */
    public static String describe(String body) {
        JsonNode job = node(body);
        return " status=" + text(job, "status")
                + ", stage=" + text(job, "stage")
                + ", progressPercent=" + text(job, "progressPercent")
                + ", errorCode=" + text(job, "errorCode")
                + ", errorMessage=" + text(job, "errorMessage");
    }

    private static AssertionError failure(String message) {
        return new AssertionError(message);
    }

    private static AssertionError failure(String message, Throwable cause) {
        return new AssertionError(message, cause);
    }

    private static String text(JsonNode job, String field) {
        JsonNode value = job.path(field);
        return value.isMissingNode() || value.isNull() ? "null" : value.asText();
    }

    private static JsonNode node(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw failure("job body is not parseable JSON: " + body, e);
        }
    }

    private static void sleepBetweenPolls() {
        try {
            Thread.sleep(INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("interrupted while waiting for the ingestion worker", e);
        }
    }
}
