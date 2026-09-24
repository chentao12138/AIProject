package com.aistudy.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingestion worker pool and job-lease policy for {@code aistudy.ingestion.worker.*}.
 *
 * <p>The pool is deliberately bounded: an unbounded queue would let a large
 * folder import grow the heap without limit and would hide backpressure from
 * the caller. Rejection is surfaced by {@code IngestionJobService}.
 *
 * <p>{@code staleLease} is how long a claim is trusted before another tick of
 * recovery may requeue the job. A running worker must renew its claim
 * ({@code heartbeat}) often enough to stay inside the lease.
 */
@ConfigurationProperties(prefix = "aistudy.ingestion.worker")
public class IngestionWorkerProperties {

    private int coreSize = 2;
    private int maxSize = 4;
    private int queueCapacity = 64;

    /** How long to let in-flight jobs finish during graceful shutdown. */
    private java.time.Duration shutdownGrace = java.time.Duration.ofSeconds(20);

    /** Claim age after which a non-terminal job is considered abandoned. */
    private java.time.Duration staleLease = java.time.Duration.ofMinutes(30);

    /** How often the lease is renewed while a job processes assets. */
    private java.time.Duration heartbeat = java.time.Duration.ofMinutes(5);

    public int getCoreSize() {
        return coreSize;
    }

    public void setCoreSize(int coreSize) {
        this.coreSize = coreSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public java.time.Duration getShutdownGrace() {
        return shutdownGrace;
    }

    public void setShutdownGrace(java.time.Duration shutdownGrace) {
        this.shutdownGrace = shutdownGrace;
    }

    public java.time.Duration getStaleLease() {
        return staleLease;
    }

    public void setStaleLease(java.time.Duration staleLease) {
        this.staleLease = staleLease;
    }

    public java.time.Duration getHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(java.time.Duration heartbeat) {
        this.heartbeat = heartbeat;
    }
}
