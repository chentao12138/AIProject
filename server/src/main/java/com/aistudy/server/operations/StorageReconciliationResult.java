package com.aistudy.server.operations;

import java.util.List;

/**
 * BUSINESS-025 — operational summary returned by storage reconciliation.
 */
public record StorageReconciliationResult(
        int scanned,
        int referenced,
        int candidateOrphans,
        int deleted,
        int skippedRecent,
        int skippedUnsafe,
        boolean truncated,
        String mode
) {
}
