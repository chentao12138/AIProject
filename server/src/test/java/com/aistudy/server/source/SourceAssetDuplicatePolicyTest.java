package com.aistudy.server.source;

import com.aistudy.server.config.properties.UploadProperties;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.asset.service.SourceAssetService;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.storage.StorageMetadata;
import com.aistudy.server.storage.StorageResult;
import com.aistudy.server.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-13 — DB-free unit tests for the deterministic duplicate-upload
 * contract: the two policies (skip / importAgain), the four real
 * cases they distinguish, and the precheck scenarios a client uses to
 * choose a policy. The concurrency case is asserted at the statement
 * level (SKIP consults the FOR-UPDATE probe and reuses its row rather
 * than blindly inserting); the lock itself is a MySQL SELECT ... FOR
 * UPDATE, exercised only against a real database.
 */
class SourceAssetDuplicatePolicyTest {

    private static final String SHA = "a".repeat(64);

    private SourceAssetMapper mapper;
    private StorageService storageService;
    private SourceAssetService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SourceAssetMapper.class);
        SourceService sourceService = mock(SourceService.class);
        storageService = mock(StorageService.class);
        when(sourceService.getMine(anyString(), anyLong(), anyLong())).thenReturn(new Source());
        when(storageService.store(any(), any(StorageMetadata.class)))
                .thenReturn(new StorageResult("2026/10/new-key", 5, SHA));
        UploadProperties uploadProperties = new UploadProperties();
        uploadProperties.setMaxFileSize(org.springframework.util.unit.DataSize.ofMegabytes(1));
        service = new SourceAssetService(mapper, sourceService, storageService, uploadProperties);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "text/plain",
                "bytes".getBytes(StandardCharsets.UTF_8));
    }

    private static SourceAsset existing(long id, String name) {
        SourceAsset asset = new SourceAsset();
        asset.setId(id);
        asset.setSpaceId(1L);
        asset.setSourceId(9L);
        asset.setOriginalName(name);
        asset.setSha256(SHA);
        asset.setSizeBytes(5L);
        asset.setStorageKey("2026/09/old-key");
        return asset;
    }

    // ==================== policy: skip ====================

    /** Case "same hash (same name)": SKIP reuses, stores nothing new. */
    @Test
    void skipReusesExistingAssetAndDeletesTransientBytes() {
        SourceAsset twin = existing(7L, "note.txt");
        when(mapper.selectForUpdateBySpaceAndSha256(1L, SHA)).thenReturn(twin);

        SourceAssetService.UploadOutcome outcome = service.uploadWithPolicy(
                "owner-1", 1L, 1L, file("note.txt"), SourceAssetService.DuplicatePolicy.SKIP);

        assertNotNull(outcome);
        assertEquals("REUSED_EXISTING", outcome.decision());
        assertSame(twin, outcome.asset());
        assertEquals(7L, outcome.duplicateOfId());
        assertTrue(outcome.duplicate());
        verify(mapper, never()).insert(any(SourceAsset.class));
        // the transient object written before the probe must not survive
        verify(storageService).delete("2026/10/new-key");
    }

    /** Case "same hash, different name": SKIP still reuses the bytes. */
    @Test
    void skipWithDifferentNameReusesRenamedTwin() {
        SourceAsset twin = existing(7L, "old-name.txt");
        when(mapper.selectForUpdateBySpaceAndSha256(1L, SHA)).thenReturn(twin);

        SourceAssetService.UploadOutcome outcome = service.uploadWithPolicy(
                "owner-1", 1L, 1L, file("new-name.txt"),
                SourceAssetService.DuplicatePolicy.SKIP);

        assertEquals("REUSED_EXISTING", outcome.decision());
        assertEquals("old-name.txt", outcome.asset().getOriginalName(),
                "reuse keeps the existing row's display name; IMPORT_AGAIN owns a new name");
    }

    /** Fresh bytes under SKIP: ordinary STORED insert. */
    @Test
    void skipWithNoHashMatchStoresNewRow() {
        when(mapper.selectForUpdateBySpaceAndSha256(1L, SHA)).thenReturn(null);

        SourceAssetService.UploadOutcome outcome = service.uploadWithPolicy(
                "owner-1", 1L, 1L, file("note.txt"), SourceAssetService.DuplicatePolicy.SKIP);

        assertEquals("STORED", outcome.decision());
        assertFalse(outcome.duplicate());
        verify(mapper).lockSpaceForUpload(1L);
        ArgumentCaptor<SourceAsset> captor = ArgumentCaptor.forClass(SourceAsset.class);
        verify(mapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getAssetVersion());
        verify(storageService, never()).delete(anyString());
    }

    // ==================== policy: importAgain ====================

    /** Explicit re-import: a NEW row even though the hash exists. */
    @Test
    void importAgainCreatesSecondRowAndFlagsDuplicate() {
        SourceAsset twin = existing(7L, "note.txt");
        when(mapper.selectForUpdateBySpaceAndSha256(1L, SHA)).thenReturn(twin);

        SourceAssetService.UploadOutcome outcome = service.uploadWithPolicy(
                "owner-1", 1L, 1L, file("note.txt"),
                SourceAssetService.DuplicatePolicy.IMPORT_AGAIN);

        assertEquals("IMPORTED_AGAIN", outcome.decision());
        assertEquals(7L, outcome.duplicateOfId());
        verify(mapper).insert(any(SourceAsset.class));
        verify(storageService, never()).delete(anyString());
    }

    // ==================== legacy no-policy contract ====================

    /** Legacy single-argument upload still always inserts and flags. */
    @Test
    void legacyUploadInsertsAndFlagsPriorDuplicate() {
        SourceAsset createdSlot = new SourceAsset();
        when(mapper.insert(any(SourceAsset.class))).thenAnswer(inv -> {
            inv.<SourceAsset>getArgument(0).setId(42L);
            return 1;
        });
        when(mapper.selectBySpaceAndSha256(eq(1L), eq(SHA), eq("owner-1")))
                .thenReturn(existing(7L, "note.txt"));

        SourceAsset asset = service.upload("owner-1", 1L, 1L, file("note.txt"));

        assertNotNull(asset);
        assertEquals(42L, asset.getId());
        // legacy path must NOT take the FOR-UPDATE lock (behaviour frozen)
        verify(mapper, never()).lockSpaceForUpload(anyLong());
        // and registers exactly one compensation synchronization as before
        List<TransactionSynchronization> syncs =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
    }

    /** The uploadWithPolicy(null) shape equals the legacy contract. */
    @Test
    void nullPolicyOutcomeFlagsDuplicateOfId() {
        when(mapper.insert(any(SourceAsset.class))).thenAnswer(inv -> {
            inv.<SourceAsset>getArgument(0).setId(42L);
            return 1;
        });
        when(mapper.selectBySpaceAndSha256(anyLong(), anyString(), anyString()))
                .thenReturn(existing(7L, "note.txt"));

        SourceAssetService.UploadOutcome outcome = service.uploadWithPolicy(
                "owner-1", 1L, 1L, file("note.txt"), null);

        assertEquals("IMPORTED_AGAIN", outcome.decision());
        assertEquals(7L, outcome.duplicateOfId());
    }

    // ==================== precheck scenarios ====================

    @Test
    void precheckSameHashSameName() {
        when(mapper.selectBySpaceAndSha256(1L, SHA, "owner-1"))
                .thenReturn(existing(7L, "note.txt"));
        SourceAssetService.DuplicateProbe probe =
                service.precheckDuplicate("owner-1", 1L, SHA, "note.txt");
        assertEquals("SAME_HASH_SAME_NAME", probe.scenario());
        assertTrue(probe.duplicate());
        assertEquals(7L, probe.matched().getId());
    }

    @Test
    void precheckSameHashDifferentName() {
        when(mapper.selectBySpaceAndSha256(1L, SHA, "owner-1"))
                .thenReturn(existing(7L, "other.txt"));
        SourceAssetService.DuplicateProbe probe =
                service.precheckDuplicate("owner-1", 1L, SHA, "note.txt");
        assertEquals("SAME_HASH_DIFFERENT_NAME", probe.scenario());
        assertTrue(probe.duplicate());
    }

    @Test
    void precheckSameNameDifferentHash() {
        when(mapper.selectBySpaceAndSha256(1L, SHA, "owner-1")).thenReturn(null);
        when(mapper.selectFirstBySpaceAndName(1L, "note.txt", "owner-1"))
                .thenReturn(existing(8L, "note.txt"));
        SourceAssetService.DuplicateProbe probe =
                service.precheckDuplicate("owner-1", 1L, SHA, "note.txt");
        assertEquals("SAME_NAME_DIFFERENT_HASH", probe.scenario());
        assertFalse(probe.duplicate());
        assertEquals(8L, probe.nameConflictAsset().getId());
    }

    @Test
    void precheckNone() {
        when(mapper.selectBySpaceAndSha256(1L, SHA, "owner-1")).thenReturn(null);
        when(mapper.selectFirstBySpaceAndName(anyLong(), anyString(), anyString()))
                .thenReturn(null);
        SourceAssetService.DuplicateProbe probe =
                service.precheckDuplicate("owner-1", 1L, SHA, "note.txt");
        assertEquals("NONE", probe.scenario());
        assertNull(probe.matched());
    }

    @Test
    void precheckRejectsMalformedSha() {
        assertThrows(ResponseStatusException.class,
                () -> service.precheckDuplicate("owner-1", 1L, "not-a-hash", "x.txt"));
    }

    @Test
    void policyParamBindingAcceptsDocumentedSpellings() {
        assertEquals(SourceAssetService.DuplicatePolicy.SKIP,
                SourceAssetService.DuplicatePolicy.fromRequestParam("skip"));
        assertEquals(SourceAssetService.DuplicatePolicy.SKIP,
                SourceAssetService.DuplicatePolicy.fromRequestParam("SKIP"));
        assertEquals(SourceAssetService.DuplicatePolicy.IMPORT_AGAIN,
                SourceAssetService.DuplicatePolicy.fromRequestParam("importAgain"));
        assertNull(SourceAssetService.DuplicatePolicy.fromRequestParam(null));
        assertNull(SourceAssetService.DuplicatePolicy.fromRequestParam("whatever"),
                "unknown values must be rejected by the controller (400)");
    }
}
