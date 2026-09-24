package com.aistudy.server.storage;

import java.io.InputStream;

/**
 * BUSINESS-004 — file storage abstraction (ADR-033).
 *
 * <p>Business modules NEVER build physical paths. They talk to this
 * interface with a {@code storageKey} (a cross-platform logical key
 * such as {@code 2026/09/<uuid>}); the physical root is owned by the
 * implementation. The database stores only {@code storageKey}
 * (NFR-DATA-003) — never {@code D:\AIStudyData\resources\...}.
 *
 * <p>First implementation: {@link LocalStorageService}. A future
 * object-storage implementation may be added behind this same
 * interface without touching business code (ADR-033).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #store} — streams {@code input} to storage, returns
 *       key + size + sha256. Throws {@link StorageLimitExceededException}
 *       when the configured byte ceiling is exceeded and
 *       {@link StorageWriteException} on IO/environment failure; never
 *       leaves a partial object behind.</li>
 *   <li>{@link #load} — opens the object for reading; throws
 *       {@link StorageInvalidKeyException} for keys escaping the root
 *       (including a target that is a symbolic link) and
 *       {@link StorageNotFoundException} when the target is absent
 *       or otherwise not a regular file.</li>
 *   <li>{@link #delete} — removes the object; throws
 *       {@link StorageNotFoundException} when the target is absent,
 *       {@link StorageInvalidKeyException} for keys escaping the root
 *       and {@link StorageWriteException} on IO failure.</li>
 * </ul>
 */
public interface StorageService {

    /**
     * Persists the raw bytes of {@code input} under a server-generated
     * key. The input stream is fully consumed; {@code metadata} carries
     * only what the storage layer needs (the first local implementation
     * needs nothing — see {@link StorageMetadata}).
     *
     * <p>The storage layer bounds the stream copy independently from
     * transport-layer limits. Misreported or chunked uploads cannot
     * exceed the configured storage byte ceiling.
     *
     * @param input    byte source (never buffered whole into memory)
     * @param metadata storage-layer metadata (optional)
     * @return key, exact byte count and lowercase hex SHA-256
     */
    StorageResult store(InputStream input, StorageMetadata metadata);

    /**
     * Opens the object identified by {@code storageKey} for reading.
     *
     * @param storageKey logical key stored in the database
     * @return stream positioned at the first byte
     * @throws StorageInvalidKeyException if the key escapes the storage root
     * @throws StorageNotFoundException   when the target object is absent or otherwise not a regular file
     */
    InputStream load(String storageKey);

    /**
     * Deletes the object identified by {@code storageKey}.
     *
     * @param storageKey logical key stored in the database
     * @throws StorageInvalidKeyException if the key escapes the storage root
     * @throws StorageNotFoundException   when the target object is absent or otherwise not a regular file
     */
    void delete(String storageKey);
}
