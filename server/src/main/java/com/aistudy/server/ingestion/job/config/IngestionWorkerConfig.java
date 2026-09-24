package com.aistudy.server.ingestion.job.config;

import com.aistudy.server.config.properties.IngestionWorkerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The bounded pool that executes ingestion jobs (architecture.md §6.2).
 *
 * <p>Without this bean the injection point in {@code IngestionJobService}
 * resolved to Spring Boot's {@code applicationTaskExecutor}, i.e. an
 * unbounded queue with 8 core threads, so a large import silently absorbed
 * every job into heap and nothing applied backpressure.
 *
 * <p>Rejection is a caller-visible condition, not a queueing opportunity:
 * ingestion is not latency critical, and the job row stays QUEUED so the
 * periodic recovery tick retries it.
 */
@Configuration
public class IngestionWorkerConfig {

    @Bean
    public ThreadPoolTaskExecutor ingestionWorkerExecutor(IngestionWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ingestion-worker-");
        executor.setCorePoolSize(properties.getCoreSize());
        executor.setMaxPoolSize(properties.getMaxSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // Let in-flight jobs finish inside the graceful-shutdown window; any
        // job still running past it is reclaimed through the stale-claim lease.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) Math.max(0, properties.getShutdownGrace().getSeconds()));
        return executor;
    }
}
