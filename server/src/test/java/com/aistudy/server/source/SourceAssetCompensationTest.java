package com.aistudy.server.source;

import com.aistudy.server.config.properties.UploadProperties;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.asset.service.SourceAssetService;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.storage.StorageResult;
import com.aistudy.server.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS-004 C5 — SourceAsset DB/filesystem compensation unit tests.
 *
 * <p>Proves that when the metadata insert fails AFTER a successful
 * storage write, the registered transaction synchronization deletes
 * the newly written object. No XA is introduced.
 */
class SourceAssetCompensationTest {

    private SourceAssetMapper mapper;
    private SourceService sourceService;
    private StorageService storageService;
    private SourceAssetService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SourceAssetMapper.class);
        sourceService = mock(SourceService.class);
        storageService = mock(StorageService.class);
        UploadProperties uploadProperties = new UploadProperties();
        uploadProperties.setMaxFileSize(org.springframework.util.unit.DataSize.ofMegabytes(1));
        service = new SourceAssetService(mapper, sourceService, storageService, uploadProperties);
        when(sourceService.getMine(anyString(), any(), any()))
                .thenReturn(new com.aistudy.server.source.entity.Source());
        when(storageService.store(any(), any()))
                .thenReturn(new StorageResult("2026/09/comp-key", 4, "abc"));
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

    @Test
    void dbInsertFailureCompensatesByDeletingStoredObject() {
        doThrow(new RuntimeException("db insert failed"))
                .when(mapper).insert(any(SourceAsset.class));

        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hi".getBytes());

        RuntimeException thrown = null;
        try {
            service.upload("owner-1", 1L, 2L, file);
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertNotNull(thrown, "insert failure must propagate");
        assertTrue(thrown.getMessage().contains("db insert failed")
                        || thrown.getCause() != null,
                "original insert failure must remain visible");

        List<TransactionSynchronization> syncs =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size(), "one compensation sync must be registered");
        syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storageService).delete("2026/09/comp-key");
    }

    @Test
    void committedTransactionDoesNotDeleteStoredObject() {
        when(mapper.insert(any(SourceAsset.class))).thenReturn(1);

        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hi".getBytes());
        SourceAsset asset = service.upload("owner-1", 1L, 2L, file);
        assertNotNull(asset);

        ArgumentCaptor<SourceAsset> captor = ArgumentCaptor.forClass(SourceAsset.class);
        verify(mapper).insert(captor.capture());
        assertEquals("2026/09/comp-key", captor.getValue().getStorageKey());

        List<TransactionSynchronization> syncs =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
        syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(storageService, never()).delete(anyString());
    }

    @Test
    void invalidUploadRejectedBeforeStorage() {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "note.txt", "text/plain", new byte[0]);
        try {
            service.upload("owner-1", 1L, 2L, empty);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assertEquals(400, e.getStatusCode().value());
        }
        verify(storageService, never()).store(any(), any());
        verify(mapper, never()).insert(any(SourceAsset.class));
    }
}
