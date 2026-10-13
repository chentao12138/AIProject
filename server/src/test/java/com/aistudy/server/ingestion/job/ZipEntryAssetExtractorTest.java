package com.aistudy.server.ingestion.job;

import com.aistudy.server.ingestion.job.service.ZipEntryAssetExtractor;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.service.SourceAssetService;
import com.aistudy.server.storage.StorageMetadata;
import com.aistudy.server.storage.StorageResult;
import com.aistudy.server.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-12 — DB-free unit tests for {@link ZipEntryAssetExtractor}: a ZIP
 * retry must produce ZERO new source_asset rows when the entry bytes
 * are unchanged, must reuse the existing storage objects (transient
 * duplicate bytes are compensated away), must create an explicit new
 * version (supersede-and-retain) when the same path carries different
 * bytes, and must never leave RAW bytes behind when the insert fails.
 *
 * <p>The {@link StorageService} here is an in-memory fake (server-
 * generated keys + real SHA-256), so byte reuse / orphan compensation
 * is asserted on actual surviving bytes, not just on calls.
 */
class ZipEntryAssetExtractorTest {

    private FakeStorage storage;
    private SourceAssetService mapper;
    private ZipEntryAssetExtractor extractor;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        mapper = mock(SourceAssetService.class);
        extractor = new ZipEntryAssetExtractor(storage, mapper);
    }

    // ==================== helpers ====================

    /** In-memory StorageService: stores bytes, returns sha256 like the real one. */
    static final class FakeStorage implements StorageService {
        final Map<String, byte[]> objects = new HashMap<>();
        final List<String> deleteCalls = new ArrayList<>();
        private int counter;

        @Override
        public StorageResult store(InputStream input, StorageMetadata metadata) {
            byte[] bytes;
            try {
                bytes = input.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            String key;
            do {
                key = "fake/" + (++counter);
            } while (objects.containsKey(key));
            objects.put(key, bytes);
            try {
                String sha = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                return new StorageResult(key, bytes.length, sha);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public InputStream load(String storageKey) {
            byte[] bytes = objects.get(storageKey);
            if (bytes == null) {
                throw new IllegalStateException("missing object " + storageKey);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String storageKey) {
            deleteCalls.add(storageKey);
            objects.remove(storageKey);
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private SourceAsset zipAsset(byte[] zipBytes) {
        StorageResult stored = storage.store(new ByteArrayInputStream(zipBytes), new StorageMetadata());
        SourceAsset asset = new SourceAsset();
        asset.setId(1L);
        asset.setSpaceId(10L);
        asset.setSourceId(20L);
        asset.setAssetRole("ORIGINAL_PACKAGE");
        asset.setOriginalName("package.zip");
        asset.setStorageKey(stored.storageKey());
        asset.setMimeType("application/zip");
        asset.setSizeBytes(stored.sizeBytes());
        asset.setSha256(stored.sha256());
        return asset;
    }

    private static Map<String, byte[]> twoTextEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("docs/a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        entries.put("docs/b.md", "# beta".getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    // ==================== first run ====================

    @Test
    void firstRunInsertsVersionOneRows() throws Exception {
        SourceAsset zip = zipAsset(zip(twoTextEntries()));
        when(mapper.findExtractedEntry(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(null);
        when(mapper.listEntryVersions(anyLong(), anyLong(), anyString()))
                .thenReturn(List.of());
        when(mapper.registerExtractedEntry(any(SourceAsset.class))).thenAnswer(inv -> {
            SourceAsset a = inv.getArgument(0);
            a.setId((long) storage.objects.size());
            return a;
        });

        List<SourceAsset> result = extractor.extractEntries(zip);

        assertEquals(2, result.size());
        ArgumentCaptor<SourceAsset> captor = ArgumentCaptor.forClass(SourceAsset.class);
        verify(mapper, times(2)).registerExtractedEntry(captor.capture());
        for (SourceAsset inserted : captor.getAllValues()) {
            assertEquals(1, inserted.getAssetVersion());
            assertNotNull(inserted.getOriginalRelativePath());
            assertNull(inserted.getSupersededAt());
            assertNotNull(inserted.getSha256());
        }
        verify(mapper, never()).markEntrySuperseded(anyLong(), any());
        // First run stores real bytes: nothing may be deleted.
        assertTrue(storage.deleteCalls.isEmpty(),
                "first run must not compensate-delete anything");
    }

    // ==================== retry: zero duplicate rows, bytes reused =========

    @Test
    void retryReusesExistingRowsAndKeepsOriginalStorageBytes() throws Exception {
        Map<String, byte[]> manifest = twoTextEntries();
        byte[] zipBytes = zip(manifest);
        SourceAsset zip = zipAsset(zipBytes);
        int objectsAfterZipUpload = storage.objects.size();

        // The two entry rows from the FIRST run already exist with their
        // own storage objects (one surviving object per entry).
        SourceAsset existingA = entryRow(100L, "docs/a.txt", manifest.get("docs/a.txt"), 1,
                "fake/reused-a");
        SourceAsset existingB = entryRow(200L, "docs/b.md", manifest.get("docs/b.md"), 1,
                "fake/reused-b");
        storage.objects.put(existingA.getStorageKey(), manifest.get("docs/a.txt"));
        storage.objects.put(existingB.getStorageKey(), manifest.get("docs/b.md"));
        int objectsBeforeRetry = storage.objects.size();

        when(mapper.findExtractedEntry(eq(10L), eq(20L), eq("docs/a.txt"), anyString()))
                .thenReturn(existingA);
        when(mapper.findExtractedEntry(eq(10L), eq(20L), eq("docs/b.md"), anyString()))
                .thenReturn(existingB);

        List<SourceAsset> result = extractor.extractEntries(zip);

        // (a) zero new rows on retry
        verify(mapper, never()).registerExtractedEntry(any(SourceAsset.class));
        // (b) the returned assets ARE the pre-existing rows — same ids
        //     and same storage keys, i.e. the RAW bytes are reused.
        assertEquals(List.of(100L, 200L), result.stream().map(SourceAsset::getId).toList());
        assertEquals(List.of("fake/reused-a", "fake/reused-b"),
                result.stream().map(SourceAsset::getStorageKey).toList());
        // (c) no surviving orphan bytes: each transient duplicate was
        //     compensated away, so the object count is unchanged.
        assertEquals(objectsBeforeRetry, storage.objects.size(),
                "retry must not leave extra RAW objects behind");
        assertEquals(2, storage.deleteCalls.size(),
                "both transient duplicates must be deleted");
        assertEquals(objectsAfterZipUpload + 2, storage.objects.size());
    }

    // ==================== same path, different bytes ====================

    @Test
    void samePathDifferentBytesCreatesNextVersionAndSupersedesHead() throws Exception {
        Map<String, byte[]> manifest = new LinkedHashMap<>();
        manifest.put("docs/a.txt", "v2 content".getBytes(StandardCharsets.UTF_8));
        SourceAsset zip = zipAsset(zip(manifest));

        SourceAsset old = entryRow(100L, "docs/a.txt",
                "v1 content".getBytes(StandardCharsets.UTF_8), 1, "fake/old-v1");
        storage.objects.put("fake/old-v1", "v1 content".getBytes(StandardCharsets.UTF_8));

        when(mapper.findExtractedEntry(eq(10L), eq(20L), eq("docs/a.txt"), anyString()))
                .thenReturn(null);
        when(mapper.listEntryVersions(10L, 20L, "docs/a.txt"))
                .thenReturn(List.of(old));
        when(mapper.registerExtractedEntry(any(SourceAsset.class))).thenAnswer(inv -> {
            SourceAsset a = inv.getArgument(0);
            a.setId(101L);
            return a;
        });

        List<SourceAsset> result = extractor.extractEntries(zip);

        assertEquals(1, result.size());
        ArgumentCaptor<SourceAsset> captor = ArgumentCaptor.forClass(SourceAsset.class);
        verify(mapper).registerExtractedEntry(captor.capture());
        assertEquals(2, captor.getValue().getAssetVersion(),
                "same path with different bytes must be an explicit version 2, not a silent duplicate");
        assertEquals("docs/a.txt", captor.getValue().getOriginalRelativePath());
        // supersede-and-retain: the old row is stamped, never deleted.
        verify(mapper).markEntrySuperseded(eq(100L), any());
        assertTrue(storage.objects.containsKey("fake/old-v1"),
                "the superseded RAW bytes stay (provenance rows may reference the old asset)");
        assertTrue(storage.deleteCalls.isEmpty());
    }

    @Test
    void multiVersionPathExtendsAfterHighestVersion() throws Exception {
        Map<String, byte[]> manifest = new LinkedHashMap<>();
        manifest.put("docs/a.txt", "v3".getBytes(StandardCharsets.UTF_8));
        SourceAsset zip = zipAsset(zip(manifest));

        SourceAsset v1 = entryRow(100L, "docs/a.txt", "v1".getBytes(StandardCharsets.UTF_8), 1,
                "fake/v1-old");
        SourceAsset v2 = entryRow(101L, "docs/a.txt", "v2".getBytes(StandardCharsets.UTF_8), 2,
                "fake/v2-old");
        v1.setSupersededAt(java.time.LocalDateTime.now());

        when(mapper.findExtractedEntry(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(null);
        when(mapper.listEntryVersions(10L, 20L, "docs/a.txt"))
                .thenReturn(List.of(v1, v2));
        when(mapper.registerExtractedEntry(any(SourceAsset.class))).thenAnswer(inv -> {
            SourceAsset a = inv.getArgument(0);
            a.setId(102L);
            return a;
        });

        extractor.extractEntries(zip);

        ArgumentCaptor<SourceAsset> captor = ArgumentCaptor.forClass(SourceAsset.class);
        verify(mapper).registerExtractedEntry(captor.capture());
        assertEquals(3, captor.getValue().getAssetVersion());
        // only the ACTIVE head (v2, superseded_at null) gets superseded — v1 already was
        verify(mapper).markEntrySuperseded(eq(101L), any());
        verify(mapper, never()).markEntrySuperseded(eq(100L), any());
    }

    // ==================== compensation ====================

    @Test
    void insertFailureCompensatesStoredBytes() throws Exception {
        Map<String, byte[]> manifest = new LinkedHashMap<>();
        manifest.put("docs/a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        SourceAsset zip = zipAsset(zip(manifest));

        when(mapper.findExtractedEntry(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(null);
        when(mapper.listEntryVersions(anyLong(), anyLong(), anyString()))
                .thenReturn(List.of());
        when(mapper.registerExtractedEntry(any(SourceAsset.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> extractor.extractEntries(zip));

        // the stored entry object must have been compensated away (the
        // ZIP package itself is not touched)
        assertEquals(1, storage.deleteCalls.size());
        assertEquals(1, storage.objects.size(), "only the ZIP object may survive a failed entry insert");
    }

    private static SourceAsset entryRow(Long id, String path, byte[] bytes, int version,
                                        String storageKey) throws Exception {
        SourceAsset asset = new SourceAsset();
        asset.setId(id);
        asset.setSpaceId(10L);
        asset.setSourceId(20L);
        asset.setAssetRole("ORIGINAL_FILE");
        asset.setOriginalName(path);
        asset.setOriginalRelativePath(path);
        asset.setStorageKey(storageKey);
        asset.setMimeType("text/plain");
        asset.setSizeBytes((long) bytes.length);
        asset.setSha256(sha256Hex(bytes));
        asset.setAssetVersion(version);
        return asset;
    }
}
