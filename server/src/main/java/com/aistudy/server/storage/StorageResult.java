package com.aistudy.server.storage;

/**
 * BUSINESS-004 — result of {@link StorageService#store}.
 *
 * <p>Holds everything the business layer needs to persist as
 * metadata: the server-generated logical key, the exact number of
 * bytes stored (streamed, so it is the authoritative size) and the
 * lowercase hex SHA-256 computed over the same stream.
 *
 * @param storageKey logical key persisted in the database
 * @param sizeBytes  exact byte count of the stored object
 * @param sha256     lowercase hex SHA-256 of the stored bytes
 */
public record StorageResult(String storageKey, long sizeBytes, String sha256) {
}
