package com.aistudy.server.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The bounded pool that executes AI generation jobs (architecture.md §6.2,
 * same shape as {@code IngestionWorkerConfig}).
 *
 * <p>Before this bean existed the worker started one hand-rolled daemon thread
 * that polled the table; there was no ceiling on concurrent provider calls and
 * no way to say "no slot is free" — which is exactly the condition a job must
 * be able to report honestly.
 *
 * <p>{@code AbortPolicy} is the point, not an oversight: a refused submission
 * leaves the row in {@code PENDING} and the recovery tick retries it, whereas a
 * caller-blocked policy would stall the dispatcher and an unbounded queue would
 * absorb every job into heap and hide the backpressure.
 *
 * <p>Keys are read with {@code @Value} defaults so this lane does not have to
 * edit {@code application*.yml}; each is overridable per deployment.
 */
@Configuration
public class AiGenerationWorkerConfig {

    /**
     * @param coreSize      always-resident generation threads
     * @param maxSize       ceiling on concurrent provider calls; clamped to at
     *                      least {@code coreSize}
     * @param queueCapacity bounded wait list; beyond it submissions are refused
     * @param shutdownGraceSeconds how long shutdown waits for in-flight jobs —
     *                      anything still running past that is picked up again
     *                      through the stale-lease path, not lost
     */
    @Bean
    public ThreadPoolTaskExecutor aiGenerationWorkerExecutor(
            @Value("${aistudy.ai.worker.core-size:2}") int coreSize,
            @Value("${aistudy.ai.worker.max-size:4}") int maxSize,
            @Value("${aistudy.ai.worker.queue-capacity:64}") int queueCapacity,
            @Value("${aistudy.ai.worker.shutdown-grace-seconds:20}") long shutdownGraceSeconds) {
        int cores = Math.max(1, coreSize);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-generation-worker-");
        executor.setCorePoolSize(cores);
        executor.setMaxPoolSize(Math.max(cores, maxSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // Let in-flight jobs finish inside the graceful-shutdown window; a job
        // still running past it is reclaimed via the stale-lease sweep.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) Math.max(0, shutdownGraceSeconds));
        return executor;
    }
}
