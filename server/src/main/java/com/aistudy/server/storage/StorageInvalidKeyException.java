package com.aistudy.server.storage;

/**
 * BUSINESS-023 — thrown when a storage key fails lexical validation
 * (blank, absolute, drive-qualified, rooted, UNC, or containing
 * traversal segments {@code .} / {@code ..}).
 *
 * <p>Extends {@link IllegalArgumentException} so existing call sites
 * that already catch {@link IllegalArgumentException} continue to work,
 * while storage-specific catch blocks can target this type directly.
 */
public class StorageInvalidKeyException extends IllegalArgumentException {

    public StorageInvalidKeyException(String message) {
        super(message);
    }
}
