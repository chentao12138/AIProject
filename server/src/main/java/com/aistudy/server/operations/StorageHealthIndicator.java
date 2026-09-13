package com.aistudy.server.operations;

import com.aistudy.server.config.properties.OperationsProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * BUSINESS-025 — lightweight storage health indicator.
 *
 * <p>Reports only bounded non-sensitive metadata. No physical root path,
 * no absolute filesystem path, and no exception text is exposed.
 */
@Component
public class StorageHealthIndicator implements HealthIndicator {

    private final LocalStorageOperations storageOperations;

    public StorageHealthIndicator(LocalStorageOperations storageOperations) {
        this.storageOperations = storageOperations;
    }

    @Override
    public Health health() {
        try {
            Path root = storageOperations.getRoot();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return Health.down()
                        .withDetail("backend", "local")
                        .withDetail("reason", "root is not a directory")
                        .build();
            }
            if (!Files.isWritable(root)) {
                return Health.down()
                        .withDetail("backend", "local")
                        .withDetail("reason", "root is not writable")
                        .build();
            }
            return Health.up()
                    .withDetail("backend", "local")
                    .build();
        } catch (SecurityException e) {
            return Health.down()
                    .withDetail("backend", "local")
                    .withDetail("reason", "security restriction")
                    .build();
        }
    }
}
