package com.aistudy.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operations policy for {@code aistudy.operations.*}.
 */
@ConfigurationProperties(prefix = "aistudy.operations")
public class OperationsProperties {

    private final StorageReconciliation storageReconciliation = new StorageReconciliation();

    public StorageReconciliation getStorageReconciliation() {
        return storageReconciliation;
    }

    public static class StorageReconciliation {

        /**
         * Maximum filesystem entries a single reconciliation scan may examine.
         */
        private int maxScanEntries = 10000;

        /**
         * Minimum age for an unreferenced file to be considered a reconciliation candidate.
         */
        private java.time.Duration minAge = java.time.Duration.ofHours(1);

        public int getMaxScanEntries() {
            return maxScanEntries;
        }

        public void setMaxScanEntries(int maxScanEntries) {
            this.maxScanEntries = maxScanEntries;
        }

        public java.time.Duration getMinAge() {
            return minAge;
        }

        public void setMinAge(java.time.Duration minAge) {
            this.minAge = minAge;
        }
    }
}
