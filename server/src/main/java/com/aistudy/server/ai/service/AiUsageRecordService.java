package com.aistudy.server.ai.service;

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
