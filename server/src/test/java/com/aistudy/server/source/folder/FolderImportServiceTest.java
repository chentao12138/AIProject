package com.aistudy.server.source.folder;

import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.folder.entity.FolderImportEntry;
import com.aistudy.server.source.folder.entity.FolderImportSnapshot;
import com.aistudy.server.source.folder.mapper.FolderImportEntryMapper;
import com.aistudy.server.source.folder.mapper.FolderImportSnapshotMapper;
import com.aistudy.server.source.folder.service.FolderImportService;
import com.aistudy.server.source.folder.service.FolderImportService.FolderEntryBinding;
import com.aistudy.server.source.folder.service.FolderImportService.FolderImportEntryRequest;
import com.aistudy.server.source.folder.service.FolderImportService.FolderSyncCompletion;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.space.entity.LearningSpace;
import com.aistudy.server.space.service.LearningSpaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-14 — DB-free unit tests for the folder-sync completion chain:
 * second-sync classification against the previous manifest (accurate
 * counts + carried-over bindings), REMOVED/MISSING bookkeeping without
 * touching SourceAssets, the bind transition with its sha256 integrity
 * gate, the explicit complete step, and the relative-path trust
 * boundary.
 *
 * <p>Both mappers are faked with in-memory lists behind Mockito
 * answers so the status machine is observable end to end without
 * MySQL.
 */
class FolderImportServiceTest {

    private static final String OWNER = "owner-1";
    private static final Long SPACE = 10L;
    private static final Long SOURCE = 20L;
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    private FolderImportSnapshotMapper snapshotMapper;
    private FolderImportEntryMapper entryMapper;
    private SourceAssetMapper sourceAssetMapper;
    private FolderImportService service;

    /** snapshotId -> persisted entries, faking the table. */
    private final Map<Long, List<FolderImportEntry>> entryTable = new HashMap<>();
    /** snapshotId -> snapshot row, faking the table. */
    private final Map<Long, FolderImportSnapshot> snapshotTable = new HashMap<>();
    private long entryIdSeq = 1;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(FolderImportSnapshotMapper.class);
        entryMapper = mock(FolderImportEntryMapper.class);
        sourceAssetMapper = mock(SourceAssetMapper.class);
        SourceService sourceService = mock(SourceService.class);
        LearningSpaceService learningSpaceService = mock(LearningSpaceService.class);
        when(sourceService.getMine(anyString(), anyLong(), anyLong())).thenReturn(new Source());
        when(learningSpaceService.getMine(anyString(), anyLong())).thenReturn(new LearningSpace());

        when(snapshotMapper.insert(any(FolderImportSnapshot.class))).thenAnswer(inv -> {
            FolderImportSnapshot s = inv.getArgument(0);
            s.setId(1L);
            return 1;
        });
        when(snapshotMapper.updateById(any(FolderImportSnapshot.class))).thenAnswer(inv -> {
            FolderImportSnapshot s = inv.getArgument(0);
            snapshotTable.put(s.getId(), s);
            return 1;
        });
        when(snapshotMapper.selectByIdAndSpace(any(), any(), any(), any()))
                .thenAnswer(inv -> snapshotTable.get(inv.getArgument(0, Long.class)));
        when(snapshotMapper.selectLatestProcessed(any(), any(), any(), any())).thenAnswer(inv -> {
            Long excludeId = inv.getArgument(2, Long.class);
            return snapshotTable.values().stream()
                    .filter(s -> !s.getId().equals(excludeId))
                    .filter(s -> FolderImportService.STATUS_COMPLETED.equals(s.getStatus())
                            || FolderImportService.STATUS_AWAITING_UPLOAD.equals(s.getStatus()))
                    .reduce((first, second) -> second)
                    .orElse(null);
        });

        when(entryMapper.insert(any(FolderImportEntry.class))).thenAnswer(inv -> {
            FolderImportEntry e = inv.getArgument(0);
            e.setId(entryIdSeq++);
            entryTable.computeIfAbsent(e.getSnapshotId(), k -> new ArrayList<>()).add(e);
            return 1;
        });
        when(entryMapper.selectBySnapshotId(any())).thenAnswer(inv ->
                new ArrayList<>(entryTable.getOrDefault(inv.getArgument(0), List.of())));
        when(entryMapper.bindPendingEntry(any(), any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            String status = inv.getArgument(1);
            Long assetId = inv.getArgument(2);
            for (FolderImportEntry e : entryTable.values().stream().flatMap(List::stream).toList()) {
                if (e.getId().equals(id)) {
                    if (!"ADDED".equals(e.getStatus()) && !"CHANGED".equals(e.getStatus())) {
                        return 0;
                    }
                    e.setStatus(status);
                    e.setSourceAssetId(assetId);
                    return 1;
                }
            }
            return 0;
        });

        service = new FolderImportService(snapshotMapper, entryMapper, sourceService,
                sourceAssetMapper, learningSpaceService);
    }

    // ==================== helpers ====================

    private FolderImportSnapshot newDraft(long id) {
        FolderImportSnapshot snapshot = new FolderImportSnapshot();
        snapshot.setId(id);
        snapshot.setSpaceId(SPACE);
        snapshot.setSourceId(SOURCE);
        snapshot.setStatus(FolderImportService.STATUS_DRAFT);
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshot.setTotalFiles(0);
        snapshot.setUnchangedFiles(0);
        snapshot.setAddedFiles(0);
        snapshot.setChangedFiles(0);
        snapshot.setRemovedFiles(0);
        snapshot.setPendingFiles(0);
        snapshotTable.put(id, snapshot);
        return snapshot;
    }

    private static FolderImportEntry prevEntry(long snapshotId, String path, String sha,
                                               String status, Long assetId) {
        FolderImportEntry e = new FolderImportEntry();
        e.setId(assetId == null ? 500L + path.hashCode() : assetId * 10);
        e.setSnapshotId(snapshotId);
        e.setRelativePath(path);
        e.setSha256(sha);
        e.setStatus(status);
        e.setSourceAssetId(assetId);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private static List<FolderImportEntryRequest> manifest(String path, String sha) {
        return List.of(new FolderImportEntryRequest(path, sha));
    }

    // ==================== second sync classification ====================

    @Test
    void secondSyncClassifiesUnchangedAddedAndMissingWithCounts() {
        // previous manifest: a.txt bound (asset 100), b.txt added-but-never-uploaded
        FolderImportSnapshot previous = newDraft(1L);
        previous.setStatus(FolderImportService.STATUS_COMPLETED);
        entryTable.put(1L, new ArrayList<>(List.of(
                prevEntry(1L, "a.txt", SHA_A, FolderImportService.ENTRY_LINKED, 100L),
                prevEntry(1L, "b.txt", SHA_B, FolderImportService.ENTRY_ADDED, null))));

        // new snapshot 2: a.txt same bytes, c.txt new (bytes NOT in space), b.txt gone
        newDraft(2L);
        when(sourceAssetMapper.selectBySpaceAndSha256(eq(SPACE), anyString(), eq(OWNER)))
                .thenReturn(null);

        FolderImportSnapshot result = service.submitEntries(OWNER, SPACE, SOURCE, 2L, List.of(
                new FolderImportEntryRequest("a.txt", SHA_A),
                new FolderImportEntryRequest("c.txt", SHA_C)));

        assertEquals(FolderImportService.STATUS_AWAITING_UPLOAD, result.getStatus(),
                "pending ADDED must not flip the snapshot to COMPLETED");
        assertEquals(2, result.getTotalFiles());
        assertEquals(1, result.getUnchangedFiles());
        assertEquals(1, result.getAddedFiles());
        assertEquals(0, result.getChangedFiles());
        assertEquals(1, result.getRemovedFiles(), "b.txt vanishes as MISSING, counted as removed");
        assertEquals(1, result.getPendingFiles());

        List<FolderImportEntry> entries = entryTable.get(2L);
        FolderImportEntry a = find(entries, "a.txt");
        assertEquals(FolderImportService.ENTRY_UNCHANGED, a.getStatus());
        assertEquals(100L, a.getSourceAssetId(),
                "UNCHANGED carries the previous binding — a second sync must not look new");
        FolderImportEntry c = find(entries, "c.txt");
        assertEquals(FolderImportService.ENTRY_ADDED, c.getStatus());
        assertNull(c.getSourceAssetId());
        FolderImportEntry b = find(entries, "b.txt");
        assertEquals(FolderImportService.ENTRY_MISSING, b.getStatus(),
                "an abandoned pending upload is MISSING, not REMOVED");
        assertNull(b.getSourceAssetId());
    }

    @Test
    void removedBoundEntryRetainsAssetPointerAndNeverDeletes() {
        FolderImportSnapshot previous = newDraft(1L);
        previous.setStatus(FolderImportService.STATUS_COMPLETED);
        entryTable.put(1L, new ArrayList<>(List.of(
                prevEntry(1L, "gone.txt", SHA_A, FolderImportService.ENTRY_UNCHANGED, 77L))));

        newDraft(2L);
        when(sourceAssetMapper.selectBySpaceAndSha256(eq(SPACE), anyString(), eq(OWNER)))
                .thenReturn(null);

        service.submitEntries(OWNER, SPACE, SOURCE, 2L,
                manifest("fresh.txt", SHA_C));

        FolderImportEntry gone = find(entryTable.get(2L), "gone.txt");
        assertEquals(FolderImportService.ENTRY_REMOVED, gone.getStatus());
        assertEquals(77L, gone.getSourceAssetId(),
                "REMOVED keeps the provenance pointer");
        // The service never deletes SourceAssets — folder_import_entry
        // bookkeeping only (SourceAssetMapper has no delete here at all).
    }

    @Test
    void changedPathClassification() {
        FolderImportSnapshot previous = newDraft(1L);
        previous.setStatus(FolderImportService.STATUS_COMPLETED);
        entryTable.put(1L, new ArrayList<>(List.of(
                prevEntry(1L, "doc.md", SHA_A, FolderImportService.ENTRY_UNCHANGED, 100L))));

        newDraft(2L);
        when(sourceAssetMapper.selectBySpaceAndSha256(eq(SPACE), anyString(), eq(OWNER)))
                .thenReturn(null);

        FolderImportSnapshot result = service.submitEntries(OWNER, SPACE, SOURCE, 2L,
                manifest("doc.md", SHA_B));

        FolderImportEntry doc = find(entryTable.get(2L), "doc.md");
        assertEquals(FolderImportService.ENTRY_CHANGED, doc.getStatus());
        assertEquals(1, result.getChangedFiles());
        assertEquals(1, result.getPendingFiles());
        assertEquals(0, result.getRemovedFiles());
    }

    @Test
    void addedEntryWithBytesAlreadyInSpaceLinksImmediatelyAndCompletes() {
        newDraft(1L);
        SourceAsset twin = new SourceAsset();
        twin.setId(55L);
        twin.setSha256(SHA_A);
        when(sourceAssetMapper.selectBySpaceAndSha256(SPACE, SHA_A, OWNER)).thenReturn(twin);

        FolderImportSnapshot result = service.submitEntries(OWNER, SPACE, SOURCE, 1L,
                manifest("x.txt", SHA_A));

        assertEquals(FolderImportService.STATUS_COMPLETED, result.getStatus(),
                "nothing pending → real completion at submit");
        FolderImportEntry x = find(entryTable.get(1L), "x.txt");
        assertEquals(FolderImportService.ENTRY_LINKED, x.getStatus());
        assertEquals(55L, x.getSourceAssetId(), "bytes in space are bound, not left dangling");
        assertEquals(0, result.getPendingFiles());
    }

    // ==================== bind ====================

    @Test
    void bindLinksPendingEntryAfterShaCheck() {
        FolderImportSnapshot snapshot = newDraft(1L);
        snapshot.setStatus(FolderImportService.STATUS_AWAITING_UPLOAD);
        snapshot.setPendingFiles(1);
        entryTable.put(1L, new ArrayList<>(List.of(
                prevEntry(1L, "pending.txt", SHA_A, FolderImportService.ENTRY_ADDED, null))));
        // fake table ids collide with prevEntry's synthetic id — reset
        entryTable.get(1L).get(0).setId(900L);

        SourceAsset uploaded = new SourceAsset();
        uploaded.setId(300L);
        uploaded.setSha256(SHA_A);
        when(sourceAssetMapper.selectByIdSpaceSource(300L, SPACE, SOURCE)).thenReturn(uploaded);

        FolderImportSnapshot result = service.bindEntries(OWNER, SPACE, SOURCE, 1L,
                List.of(new FolderEntryBinding("pending.txt", 300L)));

        assertEquals(0, result.getPendingFiles());
        FolderImportEntry pending = entryTable.get(1L).get(0);
        assertEquals(FolderImportService.ENTRY_LINKED, pending.getStatus());
        assertEquals(300L, pending.getSourceAssetId());
    }

    @Test
    void bindRejectsShaMismatchWithConflict() {
        FolderImportSnapshot snapshot = newDraft(1L);
        snapshot.setStatus(FolderImportService.STATUS_AWAITING_UPLOAD);
        entryTable.put(1L, new ArrayList<>(List.of(
                prevEntry(1L, "pending.txt", SHA_A, FolderImportService.ENTRY_ADDED, null))));

        SourceAsset uploaded = new SourceAsset();
        uploaded.setId(300L);
        uploaded.setSha256(SHA_B);
        when(sourceAssetMapper.selectByIdSpaceSource(300L, SPACE, SOURCE)).thenReturn(uploaded);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.bindEntries(OWNER, SPACE, SOURCE, 1L,
                        List.of(new FolderEntryBinding("pending.txt", 300L))));
        assertEquals(409, error.getStatusCode().value(),
                "manifest hash must equal the uploaded bytes' server-computed hash");
    }

    @Test
    void bindRejectsForeignAssetWithNotFound() {
        FolderImportSnapshot snapshot = newDraft(1L);
        snapshot.setStatus(FolderImportService.STATUS_AWAITING_UPLOAD);
        entryTable.put(1L, new ArrayList<>(List.of(
                prevEntry(1L, "pending.txt", SHA_A, FolderImportService.ENTRY_ADDED, null))));
        when(sourceAssetMapper.selectByIdSpaceSource(eq(404L), eq(SPACE), eq(SOURCE)))
                .thenReturn(null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.bindEntries(OWNER, SPACE, SOURCE, 1L,
                        List.of(new FolderEntryBinding("pending.txt", 404L))));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void bindOnCompletedSnapshotIsConflict() {
        FolderImportSnapshot snapshot = newDraft(1L);
        snapshot.setStatus(FolderImportService.STATUS_COMPLETED);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.bindEntries(OWNER, SPACE, SOURCE, 1L,
                        List.of(new FolderEntryBinding("a.txt", 5L))));
        assertEquals(409, error.getStatusCode().value());
    }

    // ==================== complete ====================

    @Test
    void completeReportsPendingInsteadOfFakingSuccess() {
        FolderImportSnapshot snapshot = newDraft(1L);
        snapshot.setStatus(FolderImportService.STATUS_AWAITING_UPLOAD);
        entryTable.put(1L, new ArrayList<>(List.of(
                prevEntry(1L, "still-missing.txt", SHA_A, FolderImportService.ENTRY_CHANGED, null))));

        FolderSyncCompletion completion = service.completeSync(OWNER, SPACE, SOURCE, 1L);

        assertFalse(completion.completed());
        assertEquals(1, completion.pendingCount());
        assertEquals("still-missing.txt", completion.pendingEntries().get(0).getRelativePath());
        assertEquals(FolderImportService.STATUS_AWAITING_UPLOAD, snapshot.getStatus(),
                "the snapshot must NOT flip to COMPLETED while bytes are pending");
    }

    @Test
    void completeFlipsToCompletedWhenNothingPending() {
        FolderImportSnapshot snapshot = newDraft(1L);
        snapshot.setStatus(FolderImportService.STATUS_AWAITING_UPLOAD);
        entryTable.put(1L, new ArrayList<>(List.of(
                prevEntry(1L, "done.txt", SHA_A, FolderImportService.ENTRY_LINKED, 100L))));

        FolderSyncCompletion completion = service.completeSync(OWNER, SPACE, SOURCE, 1L);

        assertTrue(completion.completed());
        assertEquals(FolderImportService.STATUS_COMPLETED, snapshot.getStatus());
    }

    // ==================== path trust boundary ====================

    @Test
    void submitRejectsUnsafeAndMalformedManifestLines() {
        newDraft(1L);
        List<String> badPaths = List.of("/abs/x.txt", "..", "a/../b.txt", "C:\\x.txt",
                "dir\\file.txt", "x".repeat(513) + ".txt", " ", "a//b/../c");
        for (String bad : badPaths) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> service.submitEntries(OWNER, SPACE, SOURCE, 1L, manifest(bad, SHA_A)),
                    "must reject: " + bad);
            assertEquals(400, error.getStatusCode().value());
        }
        // valid relative paths pass
        service.submitEntries(OWNER, SPACE, SOURCE, 1L,
                manifest("chapter 1/notes.md", SHA_A));
    }

    @Test
    void submitRejectsMalformedSha() {
        newDraft(1L);
        assertThrows(ResponseStatusException.class,
                () -> service.submitEntries(OWNER, SPACE, SOURCE, 1L,
                        manifest("a.txt", "not-a-hash")));
    }

    @Test
    void submitRejectsDuplicatePathsInOneManifest() {
        newDraft(1L);
        when(sourceAssetMapper.selectBySpaceAndSha256(any(), any(), any())).thenReturn(null);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.submitEntries(OWNER, SPACE, SOURCE, 1L, List.of(
                        new FolderImportEntryRequest("a.txt", SHA_A),
                        new FolderImportEntryRequest("a.txt", SHA_B))));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void resubmitToProcessedSnapshotIsConflict() {
        FolderImportSnapshot snapshot = newDraft(1L);
        snapshot.setStatus(FolderImportService.STATUS_COMPLETED);
        assertThrows(ResponseStatusException.class,
                () -> service.submitEntries(OWNER, SPACE, SOURCE, 1L, manifest("a.txt", SHA_A)));
    }

    private static FolderImportEntry find(List<FolderImportEntry> entries, String path) {
        return entries.stream().filter(e -> path.equals(e.getRelativePath())).findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + path));
    }
}
