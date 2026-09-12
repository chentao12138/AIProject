package com.aistudy.server.storage;

/**
 * BUSINESS-023 — thrown when a storage write fails for reasons other
 * than limit violation or invalid key (for example, disk full,
 * filesystem error, or atomic-move failure after a bounded copy).
 *
 * <p>The underlying {@link IOException} is retained as the cause for
 * diagnostics, but the message exposed to callers is intentionally
 * generic to avoid leaking filesystem paths or OS error details.
 */
public class StorageWriteException extends RuntimeException {

    public StorageWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
