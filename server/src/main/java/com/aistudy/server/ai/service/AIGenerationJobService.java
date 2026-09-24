package com.aistudy.server.ai.service;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.mapper.AIGenerationJobMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * §8.6 — AIGenerationJob application service.
 *
 * <p>Worker-driven state transitions: PENDING → PROCESSING → COMPLETED/FAILED.
 * Retry is allowed from FAILED back to PENDING. Progress is integer 0..100.
 */
@Service
public class AIGenerationJobService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final AIGenerationJobMapper aiGenerationJobMapper;

    public AIGenerationJobService(AIGenerationJobMapper aiGenerationJobMapper) {
        this.aiGenerationJobMapper = aiGenerationJobMapper;
    }

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
        return job;
    }

    /** Backward-compatible create without knowledgePointId. */
    public AIGenerationJob create(String jobType, String requester, Long spaceId,
                                  Long sourceId, Long revisionId) {
        return create(jobType, requester, spaceId, sourceId, revisionId, null);
    }

    /**
     * Atomic claim for workers. Returns true only for the winning worker.
     */
    @Transactional
    public boolean claim(Long jobId, String workerId) {
        return aiGenerationJobMapper.claimPending(
                jobId, workerId, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)) == 1;
    }

    /** Startup/recovery: requeue stale PROCESSING jobs. */
    public int recoverStaleJobs() {
        LocalDateTime now = LocalDateTime.now();
        return aiGenerationJobMapper.requeueStaleProcessing(now, now.minusMinutes(30));
    }

    /** Never persist raw provider/internal exception text to clients. */
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

    public List<AIGenerationJob> listMine(String requester, Long spaceId, int page, int size) {
        return aiGenerationJobMapper.selectByRequester(requester, Math.max(0, page) * Math.max(1, size), size);
    }

    @Transactional
    public AIGenerationJob start(Long jobId, String requester) {
        AIGenerationJob job = requireOwned(jobId, requester);
        if (!STATUS_PENDING.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "job is not PENDING: " + job.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = aiGenerationJobMapper.updateStatus(
                jobId, STATUS_PENDING, STATUS_PROCESSING, 0, null, null, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "job state changed concurrently");
        }
        job.setStatus(STATUS_PROCESSING);
        job.setStartedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    @Transactional
    public AIGenerationJob finish(Long jobId, String requester, int successCount, int failureCount) {
        AIGenerationJob job = requireOwned(jobId, requester);
        if (!STATUS_PROCESSING.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "job is not PROCESSING: " + job.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String finalStatus = failureCount > 0 ? STATUS_FAILED : STATUS_COMPLETED;
        int updated = aiGenerationJobMapper.finishJob(
                jobId, STATUS_PROCESSING, finalStatus, 100,
                successCount, failureCount, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "job state changed concurrently");
        }
        job.setStatus(finalStatus);
        job.setProgress(100);
        job.setSuccessCount(successCount);
        job.setFailureCount(failureCount);
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    @Transactional
    public AIGenerationJob fail(Long jobId, String requester, String errorCode, String safeMessage) {
        AIGenerationJob job = requireOwned(jobId, requester);
        if (!STATUS_PROCESSING.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "job is not PROCESSING: " + job.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = aiGenerationJobMapper.updateStatus(
                jobId, STATUS_PROCESSING, STATUS_FAILED, 0,
                errorCode, safeMessage, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "job state changed concurrently");
        }
        job.setStatus(STATUS_FAILED);
        job.setErrorCode(errorCode);
        job.setSafeMessage(safeMessage);
        job.setUpdatedAt(now);
        return job;
    }

    @Transactional
    public AIGenerationJob retry(Long jobId, String requester) {
        AIGenerationJob job = requireOwned(jobId, requester);
        if (!STATUS_FAILED.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only FAILED jobs can be retried: " + job.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = aiGenerationJobMapper.retryFailed(jobId, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "job state changed concurrently");
        }
        job.setStatus(STATUS_PENDING);
        job.setProgress(0);
        job.setErrorCode(null);
        job.setSafeMessage(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job.setRetryCount((job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1);
        job.setUpdatedAt(now);
        return job;
    }

    public AIGenerationJob getMine(Long jobId, String requester) {
        return aiGenerationJobMapper.selectByIdAndRequester(jobId, requester);
    }

    public List<AIGenerationJob> listPending() {
        return aiGenerationJobMapper.selectAllAdmin(AIGenerationJobService.STATUS_PENDING, null);
    }

    private AIGenerationJob requireOwned(Long jobId, String requester) {
        AIGenerationJob job = aiGenerationJobMapper.selectByIdAndRequester(jobId, requester);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AIGenerationJob not found");
        }
        return job;
    }
}
