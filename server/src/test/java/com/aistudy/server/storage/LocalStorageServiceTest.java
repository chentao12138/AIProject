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
        return new LocalStorageService(tempRoot.toString());
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
        assertThrows(IllegalStateException.class,
                () -> service.load(result.storageKey()),
                "load after delete must fail");
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

        assertThrows(IllegalStateException.class,
                () -> service.store(failing, new StorageMetadata()),
                "store must propagate the stream failure");

        // No .part temp files and no stray files anywhere under root.
        try (var walk = Files.walk(tempRoot)) {
            long fileCount = walk.filter(Files::isRegularFile).count();
            assertEquals(0, fileCount,
                    "failed store must leave no partial or final files under root");
        }
    }
}
