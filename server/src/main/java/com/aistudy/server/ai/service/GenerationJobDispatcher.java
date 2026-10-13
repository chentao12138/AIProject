package com.aistudy.server.ai.service;

/**
 * Seam between {@link AIGenerationJobService} and the bounded worker pool that
 * runs AI generation jobs.
 *
 * <p>The service needs to say "this row is now runnable, go look at it" without
 * depending on the worker bean, which already depends on the service; the
 * worker implements this and is injected at one {@code @Lazy} point.
 *
 * <p>Contract for implementors: {@code dispatch} must be non-blocking, must be
 * safe to call for a job that is already running or already terminal (the
 * atomic claim in the worker decides), and must <em>not</em> throw when the pool
 * is saturated. A job that cannot start has to stay {@code PENDING} so the API
 * keeps reporting an honest queued state; the periodic recovery tick retries it.
 */
@FunctionalInterface
public interface GenerationJobDispatcher {

    /**
     * Requests a run attempt for one job row.
     *
     * @param jobId job to hand to the worker pool; {@code null} is ignored
     */
    void dispatch(Long jobId);
}
