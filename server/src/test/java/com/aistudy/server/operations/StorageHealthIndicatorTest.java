package com.aistudy.server.operations;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-025 — storage health indicator.
 */
class StorageHealthIndicatorTest {

    @TempDir
    Path tempRoot;

    private StorageHealthIndicator indicator(Path root) {
        LocalStorageOperations ops = new LocalStorageOperations() {
            @Override
            public Path getRoot() {
                return root;
            }
        };
        return new StorageHealthIndicator(ops);
    }

    @Test
    void writableDirectoryIsUpAndHidesPath() {
        Health health = indicator(tempRoot).health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("local", health.getDetails().get("backend"));
        assertFalse(health.getDetails().containsValue(tempRoot.toString()),
                "health must not expose filesystem path");
        health.getDetails().values().forEach(v ->
                assertFalse(String.valueOf(v).contains(tempRoot.toString())));
    }

    @Test
    void missingDirectoryIsDown() {
        Path missing = tempRoot.resolve("does-not-exist");
        Health health = indicator(missing).health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("local", health.getDetails().get("backend"));
    }

    @Test
    void fileInsteadOfDirectoryIsDown() throws Exception {
        Path file = tempRoot.resolve("not-a-dir");
        java.nio.file.Files.writeString(file, "x");
        Health health = indicator(file).health();
        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(String.valueOf(health.getDetails().get("reason")).contains("directory"));
    }
}
