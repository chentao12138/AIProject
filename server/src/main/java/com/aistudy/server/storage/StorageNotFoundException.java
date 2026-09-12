package com.aistudy.server.storage;

/**
 * BUSINESS-023 — thrown when a storage object identified by a valid
 * key is absent.
 */
public class StorageNotFoundException extends RuntimeException {

    public StorageNotFoundException(String storageKey) {
        super("storage object not found: " + sanitize(storageKey));
    }

    private static String sanitize(String storageKey) {
        if (storageKey == null) {
            return "(null)";
        }
        return storageKey.length() > 200
                ? storageKey.substring(0, 200) + "..."
                : storageKey;
    }
}
