package com.aistudy.server.admin.ai.controller;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.mapper.AIGenerationJobMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/ai/jobs")
@SecurityRequirement(name = "bearerAuth")
public class AdminAiJobController {

    private final AIGenerationJobMapper aiGenerationJobMapper;

    public AdminAiJobController(AIGenerationJobMapper aiGenerationJobMapper) {
        this.aiGenerationJobMapper = aiGenerationJobMapper;
    }

    public record AdminAiJobView(
            Long id, String jobType, String status, Integer progress,
            String requester, Long spaceId, Long sourceId, Long revisionId,
            Integer successCount, Integer failureCount, String errorCode,
            String safeMessage, LocalDateTime startedAt, LocalDateTime finishedAt,
            Integer retryCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static AdminAiJobView from(AIGenerationJob job) {
            return new AdminAiJobView(
                    job.getId(), job.getJobType(), job.getStatus(), job.getProgress(),
                    job.getRequester(), job.getSpaceId(), job.getSourceId(), job.getRevisionId(),
                    job.getSuccessCount(), job.getFailureCount(), job.getErrorCode(),
                    job.getSafeMessage(), job.getStartedAt(), job.getFinishedAt(),
                    job.getRetryCount(), job.getCreatedAt(), job.getUpdatedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminAiJobView> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long spaceId) {
        List<AIGenerationJob> jobs = aiGenerationJobMapper.selectAllAdmin(status, spaceId);
        return jobs.stream().map(AdminAiJobView::from).toList();
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminAiJobView get(@PathVariable Long jobId) {
        AIGenerationJob job = aiGenerationJobMapper.selectById(jobId);
        if (job == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "AIGenerationJob not found");
        }
        return AdminAiJobView.from(job);
    }

    @PostMapping("/{jobId}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminAiJobView retry(@PathVariable Long jobId) {
        AIGenerationJob job = aiGenerationJobMapper.selectById(jobId);
        if (job == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "AIGenerationJob not found");
        }
        if (!"FAILED".equals(job.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "only FAILED jobs can be retried: " + job.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = aiGenerationJobMapper.retryFailed(jobId, now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "job state changed concurrently");
        }
        job.setStatus("PENDING");
        job.setProgress(0);
        job.setErrorCode(null);
        job.setSafeMessage(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job.setRetryCount((job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1);
        job.setUpdatedAt(now);
        return AdminAiJobView.from(job);
    }
}
