package com.aistudy.server.operations;

import com.aistudy.server.config.properties.OperationsProperties;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * BUSINESS-025 — storage reconciliation over the real nested layout.
 */
class StorageReconciliationServiceTest {

    @TempDir
    Path root;

    private SourceAssetMapper sourceAssetMapper;
    private OperationsProperties properties;
    private StorageReconciliationService service;

    @BeforeEach
    void setUp() {
        sourceAssetMapper = Mockito.mock(SourceAssetMapper.class);
        properties = new OperationsProperties();
        properties.getStorageReconciliation().setMaxScanEntries(100);
        properties.getStorageReconciliation().setMinAge(java.time.Duration.ofHours(1));
        LocalStorageOperations ops = () -> root;
        service = new StorageReconciliationService(properties, ops, sourceAssetMapper);
    }

    private Path writeNestedOld(String yearMonth, String name) throws Exception {
        Path dir = root.resolve(yearMonth.replace('/', java.io.File.separatorChar));
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "orphan-bytes");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(7200)));
        return file;
    }

    private Path writeNestedRecent(String yearMonth, String name) throws Exception {
        Path dir = root.resolve(yearMonth.replace('/', java.io.File.separatorChar));
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "fresh");
        return file;
    }

    @Test
    void dryRunDetectsNestedOrphanWithoutDeleting() throws Exception {
        Path orphan = writeNestedOld("2026/09", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(sourceAssetMapper.selectExistingStorageKeys(anyList())).thenReturn(List.of());

        StorageReconciliationResult result = service.reconcile(false);

        assertEquals("DRY_RUN", result.mode());
        assertEquals(0, result.deleted());
        assertEquals(1, result.candidateOrphans());
        assertTrue(Files.exists(orphan), "DRY_RUN must not delete");

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        Mockito.verify(sourceAssetMapper).selectExistingStorageKeys(keys.capture());
        assertTrue(keys.getValue().contains("2026/09/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                "canonical yyyy/MM/uuid key expected, actual=" + keys.getValue());
        assertFalse(keys.getValue().stream().anyMatch(k -> k.contains("\\") || k.contains(":")),
                "keys must not be absolute/Windows paths");
    }

    @Test
    void cleanupDeletesNestedUnreferencedOrphan() throws Exception {
        Path orphan = writeNestedOld("2026/09", "11111111-2222-3333-4444-555555555555");
        Path referenced = writeNestedOld("2026/09", "99999999-8888-7777-6666-555555555555");
        when(sourceAssetMapper.selectExistingStorageKeys(anyList()))
                .thenReturn(List.of("2026/09/99999999-8888-7777-6666-555555555555"));

        StorageReconciliationResult result = service.reconcile(true);

        assertEquals(1, result.deleted());
        assertFalse(Files.exists(orphan), "nested unreferenced orphan should be deleted");
        assertTrue(Files.exists(referenced), "referenced nested object must be preserved");
        assertEquals("CLEANUP", result.mode());
    }

    @Test
    void recentNestedFilesAreSkipped() throws Exception {
        Path recent = writeNestedRecent("2026/09", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(sourceAssetMapper.selectExistingStorageKeys(anyList())).thenReturn(List.of());

        StorageReconciliationResult result = service.reconcile(true);

        assertTrue(Files.exists(recent));
        assertTrue(result.skippedRecent() >= 1);
        assertEquals(0, result.deleted());
    }

    @Test
    void globalScanBoundAppliesAcrossNestedDirectories() throws Exception {
        properties.getStorageReconciliation().setMaxScanEntries(1);
        writeNestedOld("2026/09", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        writeNestedOld("2026/10", "bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        when(sourceAssetMapper.selectExistingStorageKeys(anyList())).thenReturn(List.of());

        StorageReconciliationResult result = service.reconcile(false);

        assertTrue(result.truncated());
        assertTrue(result.scanned() <= 1, "global bound must apply across nested dirs");
    }

    @Test
    void symlinkEscapeIsNotFollowedOrDeleted() throws Exception {
        Path outside = root.resolveSibling("outside-secret-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "top secret");
        Path nestedDir = root.resolve("2026").resolve("09");
        Files.createDirectories(nestedDir);
        Path link = nestedDir.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symlink creation unavailable: " + e);
            return;
        }
        try {
            Files.setLastModifiedTime(outside, FileTime.from(Instant.now().minusSeconds(7200)));
            when(sourceAssetMapper.selectExistingStorageKeys(anyList())).thenReturn(List.of());

            StorageReconciliationResult result = service.reconcile(true);

            assertTrue(Files.exists(outside), "symlink target outside root must not be deleted");
            assertEquals(0, result.deleted(), "symlink itself must not be treated as a deletable object");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void topLevelObjectsStillScanned() throws Exception {
        Path top = root.resolve("legacy-top-level");
        Files.writeString(top, "x");
        Files.setLastModifiedTime(top, FileTime.from(Instant.now().minusSeconds(7200)));
        when(sourceAssetMapper.selectExistingStorageKeys(anyList())).thenReturn(List.of());

        StorageReconciliationResult result = service.reconcile(true);
        assertEquals(1, result.deleted());
        assertFalse(Files.exists(top));
    }
}
