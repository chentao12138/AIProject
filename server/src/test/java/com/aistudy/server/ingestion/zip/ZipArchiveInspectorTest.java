package com.aistudy.server.ingestion.zip;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-005 — pure unit tests for {@link ZipArchiveInspector}
 * (no Spring, no MySQL, no storage).
 *
 * <p>ZIP fixtures are built in memory with {@link ZipOutputStream};
 * the encrypted-entry fixture patches the general-purpose flag bit 0
 * (encryption) in the local + central headers of a single-entry
 * archive, because the JDK cannot WRITE encrypted archives.
 */
class ZipArchiveInspectorTest {

    /** Permissive limits for tests that do not target a limit. */
    private static final ZipSafetyLimits PERMISSIVE = new ZipSafetyLimits(
            10_000, 1L * 1024 * 1024, 2L * 1024 * 1024, 200);

    // ==================== valid archives ====================

    /** (1) a normal nested archive is valid and reports its manifest. */
    @Test
    void validNestedArchiveInspectsClean() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("chapter1/", new byte[0]);
        entries.put("chapter1/1.txt", "hello".getBytes(StandardCharsets.UTF_8));
        entries.put("chapter1/2.txt", "world".getBytes(StandardCharsets.UTF_8));
        entries.put("cover.jpg", new byte[]{1, 2, 3});

        ZipInspectionResult result = inspect(entries, PERMISSIVE);

        assertTrue(result.valid(), "normal archive must be valid");
        assertTrue(result.violations().isEmpty());
        assertEquals(4, result.entryCount());
        assertEquals(4, result.entries().size());
        // hello(5) + world(5) + cover(3) = 13 (directory counts 0)
        assertEquals(13, result.totalUncompressedBytes());
        assertTrue(result.entries().stream().anyMatch(ZipEntryInfo::directory));
    }

    /** (2) exact limit boundaries are NOT violations. */
    @Test
    void exactLimitsAreAccepted() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", new byte[10]);
        ZipSafetyLimits tight = new ZipSafetyLimits(1, 10, 10, 200);

        ZipInspectionResult result = inspect(entries, tight);

        assertTrue(result.valid(), "entry size == limit and count == limit must be accepted");
    }

    // ==================== entry-name safety (zip-slip) ====================

    /** (3) {@code ../} traversal segment is rejected. */
    @Test
    void traversalSegmentRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../evil.txt", "boom".getBytes(StandardCharsets.UTF_8));

        ZipInspectionResult result = inspect(entries, PERMISSIVE);

        assertFalse(result.valid(), ".. segment must be rejected");
        assertTrue(result.violations().stream()
                        .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.TRAVERSAL_NAME),
                "expected TRAVERSAL_NAME violation");
    }

    /** (4) backslash traversal ({@code ..\..\}) is treated as traversal too. */
    @Test
    void backslashTraversalRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("..\\..\\evil.txt", "boom".getBytes(StandardCharsets.UTF_8));

        ZipInspectionResult result = inspect(entries, PERMISSIVE);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                        .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.TRAVERSAL_NAME),
                "backslash .. segment must be rejected");
    }

    /** (5) a nested traversal like {@code a/../../x} is rejected. */
    @Test
    void nestedTraversalRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a/b/../../outside.txt", "boom".getBytes(StandardCharsets.UTF_8));

        ZipInspectionResult result = inspect(entries, PERMISSIVE);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.TRAVERSAL_NAME));
    }

    /** (6) rooted (absolute) entry names are rejected. */
    @Test
    void rootedNameRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("/etc/passwd", "root".getBytes(StandardCharsets.UTF_8));

        ZipInspectionResult result = inspect(entries, PERMISSIVE);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.ROOTED_NAME));
    }

    /** (7) Windows drive prefix entry names are rejected. */
    @Test
    void driveNameRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("C:/evil/file.txt", "x".getBytes(StandardCharsets.UTF_8));

        ZipInspectionResult result = inspect(entries, PERMISSIVE);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.DRIVE_NAME));
    }

    /** (8) blank entry names are rejected. */
    @Test
    void blankNameRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(" ", "x".getBytes(StandardCharsets.UTF_8));

        ZipInspectionResult result = inspect(entries, PERMISSIVE);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.BLANK_NAME));
    }

    // ==================== structural limits ====================

    /** (9) entry count over the limit is rejected. */
    @Test
    void tooManyEntriesRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", "1".getBytes(StandardCharsets.UTF_8));
        entries.put("b.txt", "2".getBytes(StandardCharsets.UTF_8));
        entries.put("c.txt", "3".getBytes(StandardCharsets.UTF_8));
        ZipSafetyLimits twoEntries = new ZipSafetyLimits(2, 1024, 1024, 200);

        ZipInspectionResult result = inspect(entries, twoEntries);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.TOO_MANY_ENTRIES));
    }

    /** (10) per-entry uncompressed size over the limit is rejected. */
    @Test
    void entryTooLargeRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("big.txt", new byte[64]);
        ZipSafetyLimits smallEntry = new ZipSafetyLimits(10, 32, 1024, 200);

        ZipInspectionResult result = inspect(entries, smallEntry);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.ENTRY_TOO_LARGE));
    }

    /** (11) total uncompressed size over the limit is rejected. */
    @Test
    void totalTooLargeRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", new byte[60]);
        entries.put("b.txt", new byte[60]);
        ZipSafetyLimits smallTotal = new ZipSafetyLimits(10, 1024, 100, 200);

        ZipInspectionResult result = inspect(entries, smallTotal);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.TOTAL_TOO_LARGE));
    }

    /** (12) decompression-bomb ratio is rejected. */
    @Test
    void suspiciousRatioRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // highly repetitive content: compresses to a tiny fraction
        entries.put("bomb.txt", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".repeat(500)
                .getBytes(StandardCharsets.UTF_8));
        ZipSafetyLimits lowRatio = new ZipSafetyLimits(10, 1024 * 1024, 1024 * 1024, 5);

        ZipInspectionResult result = inspect(entries, lowRatio);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.SUSPICIOUS_RATIO));
    }

    // ==================== encrypted / unsupported / corrupt ====================

    /**
     * (13) an encrypted archive yields a SAFE invalid verdict (V1
     * policy: encrypted ZIP is always unsupported/rejected — pure
     * JDK cannot read it; there is no accept toggle). The fixture
     * patches the general-purpose encryption flag bit 0 in the local
     * + central headers of a single-entry archive (the JDK cannot
     * WRITE encrypted zips). The inspector must NOT throw an
     * unhandled ZipException: it returns valid=false with a typed
     * INVALID_ARCHIVE violation and a bounded, safe message (no
     * stack trace, no temp-file path).
     */
    @Test
    void encryptedArchiveProducesSafeInvalidVerdict() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("secret.txt", "classified".getBytes(StandardCharsets.UTF_8));
        byte[] zip = markEncrypted(zipBytes(entries));

        ZipInspectionResult result = inspect(zip, PERMISSIVE);

        assertFalse(result.valid(), "encrypted archive must be rejected");
        assertTrue(result.violations().stream()
                        .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.INVALID_ARCHIVE),
                "encrypted archive must map to the safe INVALID_ARCHIVE verdict");
        String message = result.violations().get(0).message();
        assertTrue(message != null && !message.isBlank(),
                "violation message must not be blank");
        assertTrue(!message.contains("aistudy-zip-inspect-"),
                "message must not leak the temp-file path: " + message);
        assertTrue(!message.contains(" at "),
                "message must not leak a stack trace: " + message);
    }

    /** (15) non-ZIP bytes are an INVALID_ARCHIVE verdict, not an exception. */
    @Test
    void corruptBytesYieldInvalidArchive() throws Exception {
        byte[] garbage = "PK\u0003\u0004 this is not really a zip".getBytes(StandardCharsets.UTF_8);

        ZipInspectionResult result = inspect(garbage, PERMISSIVE);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.INVALID_ARCHIVE));
        assertTrue(result.violations().get(0).message() != null
                && !result.violations().get(0).message().isBlank());
    }

    /** (16) a truncated archive is an INVALID_ARCHIVE verdict. */
    @Test
    void truncatedArchiveYieldInvalidArchive() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        byte[] full = zipBytes(entries);
        byte[] truncated = new byte[full.length / 2];
        System.arraycopy(full, 0, truncated, 0, truncated.length);

        ZipInspectionResult result = inspect(truncated, PERMISSIVE);

        assertFalse(result.valid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> v.type() == ZipViolation.ZipViolationType.INVALID_ARCHIVE));
    }

    // ==================== helpers ====================

    private static ZipInspectionResult inspect(Map<String, byte[]> entries,
                                                ZipSafetyLimits limits) throws IOException {
        return inspect(zipBytes(entries), limits);
    }

    private static ZipInspectionResult inspect(byte[] zip, ZipSafetyLimits limits) {
        ZipInspectionResult result = ZipArchiveInspector.inspect(
                new ByteArrayInputStream(zip), limits);
        assertNotNull(result, "inspector must always return a result");
        return result;
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * Sets the encryption general-purpose flag (bit 0) in the local
     * file header (offset 6) and the central directory header
     * (offset 8) of a single-entry archive — the JDK can read such
     * archives but cannot write them.
     */
    private static byte[] markEncrypted(byte[] zip) {
        byte[] out = zip.clone();
        int local = indexOf(out, new byte[]{0x50, 0x4b, 0x03, 0x04});
        if (local >= 0) {
            out[local + 6] |= 0x01;
        }
        int central = indexOf(out, new byte[]{0x50, 0x4b, 0x01, 0x02});
        if (central >= 0) {
            out[central + 8] |= 0x01;
        }
        return out;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
