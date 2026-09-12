package com.aistudy.server.operations;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * BUSINESS-025 — simple operational readiness probe beyond stock health.
 *
 * <p>This endpoint is intentionally read-only and cheap. It does not
 * write probe files or perform expensive filesystem scans.
 */
@Component
@Endpoint(id = "storage-health")
public class StorageHealthEndpoint {

    private final StorageHealthIndicator storageHealthIndicator;

    public StorageHealthEndpoint(StorageHealthIndicator storageHealthIndicator) {
        this.storageHealthIndicator = storageHealthIndicator;
    }

    @ReadOperation
    public StorageHealth health() {
        org.springframework.boot.actuate.health.Health health = storageHealthIndicator.health();
        return new StorageHealth(
                "UP".equals(health.getStatus().getCode()),
                health.getDetails()
        );
    }

    public record StorageHealth(boolean up, java.util.Map<String, Object> details) {
    }
}
