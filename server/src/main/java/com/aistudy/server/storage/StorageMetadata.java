package com.aistudy.server.storage;

/**
 * BUSINESS-004 — storage-layer metadata for {@link StorageService#store}.
 *
 * <p>Deliberately minimal: it carries ONLY what the storage layer
 * needs. The first implementation ({@link LocalStorageService})
 * needs nothing — the key is server-generated and the bytes are
 * streamed — so this record is empty. Business metadata (mime,
 * original filename, sha256, size) lives in the business table
 * ({@code source_asset}), not in storage. Future implementations
 * (object storage lifecycle hints, etc.) extend this record.
 */
public record StorageMetadata() {
}
