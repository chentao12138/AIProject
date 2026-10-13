package com.aistudy.server.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Requeues abandoned AI generation jobs and dispatches PENDING ones.
 *
 * <p>Mirrors {@code IngestionJobRecoveryRunner} for the AI job table and runs
 * beside it (different table, different pool, {@code @Order(3)} so the ingestion
 * sweep keeps its position). It is deliberately not merged into that runner:
 * one job lane's failure must not stop the other from recovering.
 *
 * <p>Startup <em>and</em> interval, because a claim is only reclaimable once it
 * ages past the lease: a startup-only sweep would strand a job whose worker
 * died mid-run until the next restart, and an interval-only sweep would leave
 * every job from a previous shutdown stale for a whole lease window first.
 */
@Component
@Order(3)
@ConditionalOnProperty(name = "aistudy.ai.worker.recovery-enabled",
        havingValue = "true", matchIfMissing = true)
public class AiGenerationJobRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiGenerationJobRecoveryRunner.class);

    private final AIGenerationJobService aiGenerationJobService;

    public AiGenerationJobRecoveryRunner(AIGenerationJobService aiGenerationJobService) {
        this.aiGenerationJobService = aiGenerationJobService;
    }

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /**
     * Recovery tick. Failures are logged, never propagated: a transient DB
     * error must not cancel the schedule, and this runs outside any request so
     * nobody would see the exception.
     */
    @Scheduled(fixedDelayString = "${aistudy.ai.worker.recovery-interval-ms:60000}",
            initialDelayString = "${aistudy.ai.worker.recovery-interval-ms:60000}")
    public void recover() {
        try {
            int touched = aiGenerationJobService.recoverStaleJobs();
            if (touched > 0) {
                log.info("AI generation job recovery touched {} jobs", touched);
            }
        } catch (Exception e) {
            log.warn("AI generation job recovery skipped: {}", e.getMessage());
        }
    }
}
