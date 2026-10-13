package com.aistudy.server.testsupport;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared IT helper for the ASYNC ingestion contract: job creation only
 * QUEUES work — the worker pool runs it off-request — so tests must
 * create through the real API and then POLL the job detail endpoint
 * until the expected terminal status is visible.
 *
 * <p>Created because lane tests referenced this class but it was never
 * committed; the implementation is deliberately minimal (JSON field
 * extraction by index, no extra dependencies) and only touches
 * endpoints already part of the BUSINESS-005 contract.
 */
public final class AsyncIngestionJobs {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final long POLL_INTERVAL_MILLIS = 100;

    private AsyncIngestionJobs() {
    }

    /**
     * Creates an ingestion job for one asset and waits until it reaches
     * {@code expectedStatus}.
     *
     * @param expectedStatus terminal status to wait for, e.g.
     *                       {@code NEEDS_REVIEW} / {@code SUCCEEDED} /
     *                       {@code FAILED}
     * @return the job id
     */
    public static Long createAndAwait(MockMvc mockMvc, String token, Long spaceId, Long sourceId,
                                      Long assetId, String expectedStatus) throws Exception {
        MvcResult created = mockMvc.perform(post(
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs", spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        long jobId = extractJsonLong(created.getResponse().getContentAsString(), "id");
        awaitStatus(mockMvc, token, spaceId, jobId, expectedStatus);
        return jobId;
    }

    /**
     * Polls the job detail endpoint until the job reports
     * {@code expectedStatus}; fails the assertion with the last seen
     * body on timeout (or when the job lands in an unexpected terminal
     * failure state).
     */
    public static void awaitStatus(MockMvc mockMvc, String token, Long spaceId, Long jobId,
                                   String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT.toMillis();
        String lastBody = "";
        String lastStatus = "";
        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(get(
                            "/api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}", spaceId, jobId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            lastBody = result.getResponse().getContentAsString();
            lastStatus = extractJsonString(lastBody, "status");
            if (expectedStatus.equals(lastStatus)) {
                return;
            }
            if (("FAILED".equals(lastStatus) || "PARTIAL_FAILED".equals(lastStatus))
                    && !expectedStatus.equals(lastStatus)) {
                assertTrue(false, "ingestion job " + jobId + " terminated as "
                        + lastStatus + " instead of " + expectedStatus + ": " + lastBody);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        assertTrue(false, "ingestion job " + jobId + " did not reach " + expectedStatus
                + " within " + DEFAULT_TIMEOUT + "; last status " + lastStatus + ": " + lastBody);
    }

    private static long extractJsonLong(String json, String field) {
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker);
        assertTrue(start >= 0, "field '" + field + "' missing in: " + json);
        int valueStart = start + marker.length();
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
            valueStart++;
        }
        int end = valueStart;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(valueStart, end));
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        assertTrue(start >= 0, "field '" + field + "' missing in: " + json);
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        return json.substring(valueStart, end);
    }
}
