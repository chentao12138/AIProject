package com.aistudy.server.storage;

/**
 * BUSINESS-023 — thrown when a storage write would exceed the
 * configured byte ceiling.
 */
public class StorageLimitExceededException extends RuntimeException {

    private final long limitBytes;
    private final long actualBytes;

    public StorageLimitExceededException(long limitBytes, long actualBytes) {
        super("storage write exceeds the configured limit of "
                + limitBytes + " bytes (attempted " + actualBytes + " bytes)");
        this.limitBytes = limitBytes;
        this.actualBytes = actualBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }

    public long actualBytes() {
        return actualBytes;
    }
}
