package com.aistudy.server.ai.service;

import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiUsage;
import com.aistudy.server.ai.entity.AiUsageRecord;
import com.aistudy.server.ai.mapper.AiUsageRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * §8.1 — AIUsageRecord application service.
 *
 * <p>Append-only usage ledger. Records one row per AI provider call.
 * Never stores API keys, Authorization headers, or raw upstream
 * request/response bodies.
 */
@Service
public class AiUsageRecordService {

    private final AiUsageRecordMapper aiUsageRecordMapper;

    public AiUsageRecordService(AiUsageRecordMapper aiUsageRecordMapper) {
        this.aiUsageRecordMapper = aiUsageRecordMapper;
    }

    public void record(String userSubject, Long spaceId, String provider, String model,
                       String purpose, String requestId, String status) {
        record(userSubject, spaceId, provider, model, purpose, requestId,
                null, null, null, null, status, null);
    }

    /** Ledger statuses; matches the values the tutor/coach paths already write. */
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    /** Purposes of the batch generators, which previously wrote no ledger row. */
    public static final String PURPOSE_KNOWLEDGE_EXTRACTION = "KNOWLEDGE_EXTRACTION";
    public static final String PURPOSE_QUESTION_GENERATION = "QUESTION_GENERATION";
    public static final String PURPOSE_CONTENT_STRUCTURE = "CONTENT_STRUCTURE";
    public static final String PURPOSE_VARIANT = "VARIANT";
    /**
     * The read-only ADMIN grading aid (audit E-07). Booked like every other
     * purpose because it spends tokens, even though it writes no score.
     */
    public static final String PURPOSE_GRADING_SUGGESTION = "GRADING_SUGGESTION";

    /**
     * Records one provider call made by a generator or the variant service
     * (audit F-23), taking provider, model and token counts from the response so
     * every caller cannot derive them differently.
     *
     * <p>{@code provider} and {@code model} are NOT NULL in V052, so a failure
     * that never produced a response is stored as {@code unknown} instead of
     * being skipped: a missing row would make an outage look like no traffic.
     *
     * @param purpose   one of the {@code PURPOSE_*} constants
     * @param response  the provider response, or {@code null} when the call failed
     * @param latencyMs wall-clock milliseconds of the attempt
     * @param errorCode stable code when the call failed, {@code null} on success
     */
    public void recordProviderCall(String userSubject, Long spaceId, String purpose,
                                   AiChatResponse response, int latencyMs, String errorCode) {
        AiUsage usage = response == null ? null : response.usage();
        record(userSubject, spaceId,
                response == null ? "unknown" : orUnknown(response.provider()),
                response == null ? "unknown" : orUnknown(response.model()),
                purpose, null,
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                latencyMs,
                errorCode == null ? STATUS_SUCCESS : STATUS_FAILED,
                errorCode);
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /**
     * Milliseconds elapsed since {@code startedNanos}, for
     * {@link #recordProviderCall}. Exists so no caller has to repeat the
     * nanosecond conversion and so a latency is never guessed.
     */
    public static int elapsedMs(long startedNanos) {
        long ms = (System.nanoTime() - startedNanos) / 1_000_000L;
        return (int) Math.max(0L, Math.min(ms, Integer.MAX_VALUE));
    }

    public void record(String userSubject, Long spaceId, String provider, String model,
                       String purpose, String requestId,
                       Integer promptTokens, Integer completionTokens, Integer totalTokens,
                       Integer latencyMs, String status, String errorCode) {
        AiUsageRecord record = new AiUsageRecord();
        record.setUserSubject(userSubject);
        record.setSpaceId(spaceId);
        record.setProvider(provider);
        record.setModel(model);
        record.setPurpose(purpose);
        record.setRequestId(requestId);
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(totalTokens);
        record.setLatencyMs(latencyMs);
        record.setStatus(status);
        record.setErrorCode(errorCode);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        record.setCreatedAt(now);
        aiUsageRecordMapper.insertRecord(record);
    }

    public List<AiUsageRecord> list(String userSubject, int offset, int limit) {
        return aiUsageRecordMapper.selectByUser(userSubject, offset, limit);
    }
}
