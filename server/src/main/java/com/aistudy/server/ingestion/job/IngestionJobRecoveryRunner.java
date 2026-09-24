package com.aistudy.server.ingestion.job;

import com.aistudy.server.ingestion.job.service.IngestionJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Requeues abandoned ingestion jobs and dispatches QUEUED ones.
 *
 * <p>Runs once at startup and then on an interval (architecture.md §6.2): a
 * claim is only reclaimable after it ages past
 * {@code aistudy.ingestion.worker.stale-lease}, so a startup-only sweep would
 * strand any job whose worker died mid-run until the next restart.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "aistudy.ingestion.worker.recovery-enabled",
        havingValue = "true", matchIfMissing = true)
public class IngestionJobRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobRecoveryRunner.class);

    private final IngestionJobService ingestionJobService;

    public IngestionJobRecoveryRunner(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    @Scheduled(fixedDelayString = "${aistudy.ingestion.worker.recovery-interval-ms:60000}",
            initialDelayString = "${aistudy.ingestion.worker.recovery-interval-ms:60000}")
    public void recover() {
        try {
            int recovered = ingestionJobService.recoverStaleJobs();
            if (recovered > 0) {
                log.info("Ingestion job recovery touched {} jobs", recovered);
            }
        } catch (Exception e) {
            log.warn("Ingestion job recovery skipped: {}", e.getMessage());
        }
    }
}
