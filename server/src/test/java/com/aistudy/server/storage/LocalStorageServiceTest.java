package com.aistudy.server.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-004 — {@link LocalStorageService} unit tests (pure Java,
 * {@code @TempDir} root — NEVER {@code D:\AIStudyData} or any real
 * location).
 *
 * <p>Covers: exact byte round-trip, exact size, exact SHA-256,
 * server-generated keys that never embed the original filename,
 * keys that cannot escape the root, delete semantics, and path
 * traversal rejection for both load and delete.
 */
class LocalStorageServiceTest {

    @TempDir
    Path tempRoot;

    private LocalStorageService newService() {
        com.aistudy.server.config.properties.StorageProperties storageProperties = new com.aistudy.server.config.properties.StorageProperties();
        storageProperties.getLocal().setRoot(tempRoot.toString());

        com.aistudy.server.config.properties.UploadProperties uploadProperties = new com.aistudy.server.config.properties.UploadProperties();
        uploadProperties.setMaxFileSize(org.springframework.util.unit.DataSize.ofMegabytes(1024));

        return new LocalStorageService(storageProperties, uploadProperties);
    }

    private static final byte[] SAMPLE =
            "BUSINESS-004 RAW sample: 数据库系统工程师教程.zip bytes\n"
                    .getBytes(StandardCharsets.UTF_8);

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** (1) store → load returns the EXACT same bytes. */
    @Test
    void storeThenLoadReturnsExactBytes() throws Exception {
        LocalStorageService service = newService();
        StorageResult result = service.store(
                new ByteArrayInputStream(SAMPLE), new StorageMetadata());

        try (InputStream in = service.load(result.storageKey())) {
            assertArrayEquals(SAMPLE, in.readAllBytes(),
                    "load must return the exact bytes that were stored");
        }
    }

    /** (2) sizeBytes is the exact stored byte count. */
    @Test
    void storeReportsExactSizeBytes() {
        LocalStorageService service = newService();
        StorageResult result = service.store(
                new ByteArrayInputStream(SAMPLE), new StorageMetadata());
        assertEquals(SAMPLE.length, result.sizeBytes(),
                "sizeBytes must equal the exact stream byte count");
    }

    /** (3) sha256 is the exact lowercase hex SHA-256 of the bytes. */
    @Test
    void storeReportsExactSha256() throws Exception {
        LocalStorageService service = newService();
        StorageResult result = service.store(
                new ByteArrayInputStream(SAMPLE), new StorageMetadata());
        assertEquals(sha256Hex(SAMPLE), result.sha256(),
                "sha256 must be the exact lowercase hex digest");
    }

    /** (4) storageKey is server-generated and never contains the original filename. */
    @Test
    void storageKeyIsServerGeneratedAndFilenameFree() {
        LocalStorageService service = newService();
        StorageResult result = service.store(
                new ByteArrayInputStream(SAMPLE), new StorageMetadata());

        assertNotNull(result.storageKey());
        assertFalse(result.storageKey().contains("book.pdf"),
                "storageKey must not embed the original filename");
        assertFalse(result.storageKey().contains("\\"),
                "storageKey must be POSIX-style (no backslashes)");
        assertTrue(result.storageKey().matches("\\d{4}/\\d{2}/[0-9a-fA-F-]{36}"),
                "storageKey must match yyyy/MM/<uuid>, was: " + result.storageKey());
    }

    /** (5) the generated key never escapes the root (file lands under root). */
    @Test
    void storedObjectLandsInsideRoot() throws Exception {
        LocalStorageService service = newService();
        StorageResult result = service.store(
                new ByteArrayInputStream(SAMPLE), new StorageMetadata());

        Path resolved = tempRoot.resolve(result.storageKey()).normalize();
        assertTrue(resolved.startsWith(tempRoot.normalize()),
                "stored object must stay inside the storage root");
        assertTrue(Files.exists(resolved),
                "stored object must exist at the resolved path");
    }

    /** (6) after delete, load fails (object gone). */
    @Test
    void deleteRemovesObjectAndLoadFails() {
        LocalStorageService service = newService();
        StorageResult result = service.store(
                new ByteArrayInputStream(SAMPLE), new StorageMetadata());

        service.delete(result.storageKey());
        assertThrows(StorageNotFoundException.class,
                () -> service.load(result.storageKey()),
                "load after delete must fail with StorageNotFoundException");
    }

    /** (6b) delete of a missing key throws StorageNotFoundException (requireRegularFile). */
    @Test
    void deleteMissingKeyThrowsNotFound() {
        LocalStorageService service = newService();
        assertThrows(StorageNotFoundException.class,
                () -> service.delete("2026/09/00000000-0000-0000-0000-000000000000"),
                "delete of a missing key must throw StorageNotFoundException");
    }

    /** (6c) load of a never-stored key fails with StorageNotFoundException. */
    @Test
    void loadMissingKeyThrowsNotFound() {
        LocalStorageService service = newService();
        assertThrows(StorageNotFoundException.class,
                () -> service.load("2026/09/00000000-0000-0000-0000-000000000000"));
    }

    /** (6d) two stores never collide on the same storageKey. */
    @Test
    void storeGeneratesDistinctKeys() {
        LocalStorageService service = newService();
        StorageResult a = service.store(new ByteArrayInputStream(SAMPLE), new StorageMetadata());
        StorageResult b = service.store(new ByteArrayInputStream(SAMPLE), new StorageMetadata());
        assertFalse(a.storageKey().equals(b.storageKey()),
                "each store must generate a unique storageKey");
    }

    /** (6e) UNC / rooted Windows-style keys are rejected. */
    @Test
    void loadRejectsUncAndRootedKeys() {
        LocalStorageService service = newService();
        assertThrows(IllegalArgumentException.class,
                () -> service.load("\\\\server\\share\\secret.txt"),
                "load must reject UNC keys");
        assertThrows(IllegalArgumentException.class,
                () -> service.load("//server/share/secret.txt"),
                "load must reject forward-slash UNC-like keys");
    }

    /** (6f) store enforces the configured byte ceiling. */
    @Test
    void storeEnforcesByteCeiling() throws Exception {
        com.aistudy.server.config.properties.StorageProperties storageProperties =
                new com.aistudy.server.config.properties.StorageProperties();
        storageProperties.getLocal().setRoot(tempRoot.toString());
        com.aistudy.server.config.properties.UploadProperties uploadProperties =
                new com.aistudy.server.config.properties.UploadProperties();
        uploadProperties.setMaxFileSize(org.springframework.util.unit.DataSize.ofBytes(8));
        LocalStorageService service = new LocalStorageService(storageProperties, uploadProperties);

        byte[] oversized = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        assertThrows(StorageLimitExceededException.class,
                () -> service.store(new ByteArrayInputStream(oversized), new StorageMetadata()));
        try (var walk = Files.walk(tempRoot)) {
            assertEquals(0, walk.filter(Files::isRegularFile).count(),
                    "oversized store must leave no files under root");
        }
    }

    /**
     * (6g) symlink escape: when the platform allows creating a symlink
     * that points outside the root, load/delete must reject it.
     * Skipped (assumption) when symlink creation is unavailable.
     */
    @Test
    void loadRejectsSymlinkEscapeWhenSupported() throws Exception {
        LocalStorageService service = newService();
        Path outside = tempRoot.resolveSibling("outside-secret.txt");
        Files.writeString(outside, "top secret");
        Path linkDir = tempRoot.resolve("2026").resolve("09");
        Files.createDirectories(linkDir);
        Path link = linkDir.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symlink creation unavailable on this platform: " + e);
            return;
        }
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> service.load("2026/09/escape-link"),
                    "load must reject a symlink pointing outside the root");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    /** (7) load("../...") is rejected BEFORE touching the filesystem. */
    @Test
    void loadRejectsPathTraversal() {
        LocalStorageService service = newService();
        assertThrows(IllegalArgumentException.class,
                () -> service.load("../secret.txt"),
                "load must reject keys escaping the root");
        assertThrows(IllegalArgumentException.class,
                () -> service.load("2026/09/../../secret.txt"),
                "load must reject normalized escapes");
        assertThrows(IllegalArgumentException.class,
                () -> service.load("2026\\09\\..\\..\\secret.txt"),
                "load must reject backslash traversal segments");
        assertThrows(IllegalArgumentException.class,
                () -> service.load("../../../secret.txt"),
                "load must reject keys escaping above the root");
        assertThrows(IllegalArgumentException.class,
                () -> service.load("/etc/passwd"),
                "load must reject absolute keys");
        assertThrows(IllegalArgumentException.class,
                () -> service.load(""),
                "load must reject blank keys");
    }

    /** (8) delete("../...") is rejected BEFORE touching the filesystem. */
    @Test
    void deleteRejectsPathTraversal() {
        LocalStorageService service = newService();
        assertThrows(IllegalArgumentException.class,
                () -> service.delete("../secret.txt"),
                "delete must reject keys escaping the root");
        assertThrows(IllegalArgumentException.class,
                () -> service.delete("2026/09/../../secret.txt"),
                "delete must reject normalized escapes");
        assertThrows(IllegalArgumentException.class,
                () -> service.delete("2026\\09\\..\\..\\secret.txt"),
                "delete must reject backslash traversal segments");
        assertThrows(IllegalArgumentException.class,
                () -> service.delete("C:\\evil\\file"),
                "delete must reject drive-escape keys");
        assertThrows(IllegalArgumentException.class,
                () -> service.delete("C:/evil/file"),
                "delete must reject forward-slash drive keys");
    }

    /** (9) a failing stream leaves NO final and NO .part file behind. */
    @Test
    void partialWriteFailureLeavesNothingBehind() throws Exception {
        LocalStorageService service = newService();

        // Stream that yields some bytes then fails mid-write.
        InputStream failing = new InputStream() {
            private int emitted = 0;
            private final byte[] data = "short prefix".getBytes(StandardCharsets.UTF_8);

            @Override
            public int read() throws IOException {
                if (emitted >= data.length) {
                    throw new IOException("simulated stream failure");
                }
                return data[emitted++];
            }
        };

        assertThrows(StorageWriteException.class,
                () -> service.store(failing, new StorageMetadata()),
                "store must propagate the stream failure as StorageWriteException");

        // No .part temp files and no stray files anywhere under root.
        try (var walk = Files.walk(tempRoot)) {
            long fileCount = walk.filter(Files::isRegularFile).count();
            assertEquals(0, fileCount,
                    "failed store must leave no partial or final files under root");
        }
    }
}
