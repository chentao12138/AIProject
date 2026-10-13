package com.aistudy.server.source.folder.service;

import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.folder.entity.FolderImportEntry;
import com.aistudy.server.source.folder.entity.FolderImportSnapshot;
import com.aistudy.server.source.folder.mapper.FolderImportEntryMapper;
import com.aistudy.server.source.folder.mapper.FolderImportSnapshotMapper;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F-14 — desktop folder-sync completion chain.
 *
 * <h3>Snapshot lifecycle</h3>
 *
 * <pre>
 *   DRAFT --submitEntries(manifest)--> COMPLETED        (nothing pending)
 *                                   -> AWAITING_UPLOAD  (ADDED/CHANGED without bytes)
 *   AWAITING_UPLOAD --bindEntries--> AWAITING_UPLOAD / COMPLETED-ready
 *   AWAITING_UPLOAD --completeSync--> COMPLETED (only when pending == 0;
 *                                               otherwise reports the pending list)
 * </pre>
 *
 * <h3>Classification (diff against the PREVIOUS processed snapshot)</h3>
 *
 * <ul>
 *   <li>{@code ADDED} — path never seen in the previous manifest.</li>
 *   <li>{@code CHANGED} — known path, different sha256.</li>
 *   <li>{@code UNCHANGED} — known path, same sha256, previous entry had
 *       a bound asset: the binding is carried over verbatim (provenance
 *       continuity — the same SourceAsset row keeps serving the file).</li>
 *   <li>{@code LINKED} — ADDED/CHANGED whose bytes were already in the
 *       space (sha256 hit) at submit time, or were uploaded and bound
 *       afterwards via {@link #bindEntries}.</li>
 *   <li>{@code REMOVED} — previous entry had bound bytes but the path is
 *       gone from the new manifest. NEVER deletes the SourceAsset: it
 *       may already own pages/blocks/ingestion provenance. Retention is
 *       the documented rule; physical cleanup is a future, separate
 *       policy decision.</li>
 *   <li>{@code MISSING} — previous entry was still pending (no bytes
 *       ever bound) and the path is gone: nothing to retain, recorded
 *       only so counts reconcile.</li>
 * </ul>
 *
 * <p>Count semantics on the snapshot: {@code totalFiles} = manifest
 * size (not counting REMOVED/MISSING rows, which are bookkeeping
 * entries of THIS snapshot); {@code unchangedFiles}/{@code addedFiles}/
 * {@code changedFiles} follow the statuses above (LINKED-at-submit rows
 * keep counting as the ADDED/CHANGED they were); {@code removedFiles} =
 * REMOVED + MISSING; {@code pendingFiles} = ADDED/CHANGED still lacking
 * a binding.
 *
 * <h3>Path trust boundary</h3>
 *
 * <p>The backend NEVER trusts a client-supplied disk path. Every
 * {@code relativePath} is validated to be a clean POSIX-style relative
 * path (no drive prefixes, no leading / or \, no {@code .}/{@code ..}
 * segments, no backslashes, ≤ 512 chars) before anything is persisted,
 * and entries can only ever bind to assets the caller already owns in
 * the same (space, source).
 *
 * <h3>Failure modes</h3>
 *
 * <ul>
 *   <li>404: space/source/snapshot not owned, or binding references an
 *       asset outside the caller's (space, source).</li>
 *   <li>400: malformed relativePath / sha256, unknown policy state.</li>
 *   <li>409: submitting twice (snapshot no longer DRAFT), binding on a
 *       snapshot that is not AWAITING_UPLOAD, or sha256 mismatch between
 *       the manifest entry and the uploaded asset (the bytes the client
 *       hashed are NOT the bytes it uploaded).</li>
 * </ul>
 */
@Service
public class FolderImportService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    /** Manifest accepted, but some ADDED/CHANGED entries still lack bytes. */
    public static final String STATUS_AWAITING_UPLOAD = "AWAITING_UPLOAD";

    public static final String ENTRY_UNCHANGED = "UNCHANGED";
    public static final String ENTRY_ADDED = "ADDED";
    public static final String ENTRY_CHANGED = "CHANGED";
    public static final String ENTRY_LINKED = "LINKED";
    public static final String ENTRY_REMOVED = "REMOVED";
    public static final String ENTRY_MISSING = "MISSING";

    private static final int MAX_RELATIVE_PATH_LENGTH = 512;

    private final FolderImportSnapshotMapper snapshotMapper;
    private final FolderImportEntryMapper entryMapper;
    private final SourceService sourceService;
    private final SourceAssetMapper sourceAssetMapper;
    private final LearningSpaceService learningSpaceService;

    public FolderImportService(FolderImportSnapshotMapper snapshotMapper,
                               FolderImportEntryMapper entryMapper,
                               SourceService sourceService,
                               SourceAssetMapper sourceAssetMapper,
                               LearningSpaceService learningSpaceService) {
        this.snapshotMapper = snapshotMapper;
        this.entryMapper = entryMapper;
        this.sourceService = sourceService;
        this.sourceAssetMapper = sourceAssetMapper;
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * Creates an empty DRAFT snapshot. The client MUST submit its
     * manifest before any ingestion references it.
     *
     * @return snapshot or {@code null} when space/source not owned
     */
    public FolderImportSnapshot createSnapshot(String ownerSubject, Long spaceId, Long sourceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        FolderImportSnapshot snapshot = new FolderImportSnapshot();
        snapshot.setSpaceId(spaceId);
        snapshot.setSourceId(sourceId);
        snapshot.setTotalFiles(0);
        snapshot.setUnchangedFiles(0);
        snapshot.setAddedFiles(0);
        snapshot.setChangedFiles(0);
        snapshot.setRemovedFiles(0);
        snapshot.setPendingFiles(0);
        snapshot.setStatus(STATUS_DRAFT);
        snapshot.setCreatedAt(now);
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    /**
     * F-14 — accepts one folder manifest and produces the real diff
     * against the previous processed snapshot.
     *
     * <p>Each entry becomes ADDED / CHANGED / UNCHANGED (with prior
     * binding carried over) or LINKED (bytes already present in the
     * space, looked up once per sha256). Paths absent from the new
     * manifest but present previously are recorded as REMOVED (bound
     * bytes) or MISSING (never bound), counted into
     * {@code removedFiles} — their SourceAssets are retained, never
     * deleted, because ingestion provenance may reference them.
     *
     * @param entries manifest entries; relativePath + sha256 required,
     *                duplicate paths are rejected with 400
     * @return the updated snapshot with accurate counts; status
     *         COMPLETED when nothing is pending, else AWAITING_UPLOAD
     */
    @Transactional
    public FolderImportSnapshot submitEntries(String ownerSubject, Long spaceId, Long sourceId,
                                              Long snapshotId, List<FolderImportEntryRequest> entries) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "source not found");
        }
        FolderImportSnapshot snapshot = snapshotMapper.selectByIdAndSpace(snapshotId, spaceId, sourceId, ownerSubject);
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "snapshot not found");
        }
        if (!STATUS_DRAFT.equals(snapshot.getStatus())) {
            throw ApiException.conflict(ApiErrorCodes.FOLDER_SNAPSHOT_ALREADY_PROCESSED,
                    "snapshot already processed");
        }

        List<FolderImportEntryRequest> manifest = entries == null ? List.of() : entries;
        Set<String> manifestPaths = new HashSet<>();
        for (FolderImportEntryRequest request : manifest) {
            validateRelativePath(request.relativePath());
            validateSha256(request.sha256());
            if (!manifestPaths.add(request.relativePath())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "duplicate relativePath in manifest: " + request.relativePath());
            }
        }

        // Previous manifest (path -> entry) for the diff.
        Map<String, FolderImportEntry> previousByPath = new HashMap<>();
        FolderImportSnapshot previous = snapshotMapper.selectLatestProcessed(
                spaceId, sourceId, snapshotId, ownerSubject);
        if (previous != null) {
            for (FolderImportEntry old : entryMapper.selectBySnapshotId(previous.getId())) {
                previousByPath.put(old.getRelativePath(), old);
            }
        }

        // sha -> existing asset cache: one owner-scoped lookup per distinct
        // hash, so a folder of 500 identical files does not issue 500 probes.
        Map<String, SourceAsset> assetBySha = new HashMap<>();

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int unchanged = 0;
        int added = 0;
        int changed = 0;

        for (FolderImportEntryRequest entryReq : manifest) {
            // Normalized lowercase — prev-sha equality and asset-sha
            // equality compare against storageService's lowercase hex.
            String sha = entryReq.sha256().toLowerCase(Locale.ROOT);
            FolderImportEntry entry = new FolderImportEntry();
            entry.setSnapshotId(snapshotId);
            entry.setRelativePath(entryReq.relativePath());
            entry.setSha256(sha);
            entry.setCreatedAt(now);

            SourceAsset bytesInSpace = assetBySha.computeIfAbsent(sha,
                    key -> sourceAssetMapper.selectBySpaceAndSha256(spaceId, key, ownerSubject));
            FolderImportEntry prev = previousByPath.get(entryReq.relativePath());

            if (prev != null && sha.equals(prev.getSha256()) && prev.getSourceAssetId() != null) {
                // Same path, same bytes, already bound: carry the binding
                // over — a second sync must not look "new".
                entry.setStatus(ENTRY_UNCHANGED);
                entry.setSourceAssetId(prev.getSourceAssetId());
                unchanged++;
            } else if (bytesInSpace != null) {
                // Bytes already in the space (prior sync that finished
                // uploading, or the same content via the upload API):
                // link now, still counted as ADDED/CHANGED by diff.
                entry.setStatus(ENTRY_LINKED);
                entry.setSourceAssetId(bytesInSpace.getId());
                if (prev == null) {
                    added++;
                } else {
                    changed++;
                }
            } else if (prev == null) {
                entry.setStatus(ENTRY_ADDED);
                entry.setSourceAssetId(null);
                added++;
            } else {
                // Path known with different bytes, or known-but-never-bound
                // with the same bytes: the client must (re-)upload.
                entry.setStatus(ENTRY_CHANGED);
                entry.setSourceAssetId(null);
                changed++;
            }
            entryMapper.insert(entry);
        }

        // Removed / missing: previous manifest paths not seen now.
        int removed = 0;
        for (Map.Entry<String, FolderImportEntry> oldEntry : previousByPath.entrySet()) {
            if (manifestPaths.contains(oldEntry.getKey())) {
                continue;
            }
            FolderImportEntry gone = oldEntry.getValue();
            FolderImportEntry bookkeeping = new FolderImportEntry();
            bookkeeping.setSnapshotId(snapshotId);
            bookkeeping.setRelativePath(gone.getRelativePath());
            bookkeeping.setSha256(gone.getSha256());
            bookkeeping.setCreatedAt(now);
            if (gone.getSourceAssetId() != null) {
                // REMOVED: retain + record the provenance pointer. The
                // SourceAsset row (and its RAW bytes) are NEVER deleted
                // here — pages/blocks/ingestion jobs may reference it.
                bookkeeping.setStatus(ENTRY_REMOVED);
                bookkeeping.setSourceAssetId(gone.getSourceAssetId());
            } else {
                // MISSING: a pending upload the client abandoned.
                bookkeeping.setStatus(ENTRY_MISSING);
                bookkeeping.setSourceAssetId(null);
            }
            entryMapper.insert(bookkeeping);
            removed++;
        }

        int pendingEntries = countPendingOf(
                entryMapper.selectBySnapshotId(snapshotId));

        snapshot.setTotalFiles(manifest.size());
        snapshot.setUnchangedFiles(unchanged);
        snapshot.setAddedFiles(added);
        snapshot.setChangedFiles(changed);
        snapshot.setRemovedFiles(removed);
        snapshot.setPendingFiles(pendingEntries);
        snapshot.setStatus(pendingEntries == 0 ? STATUS_COMPLETED : STATUS_AWAITING_UPLOAD);
        snapshotMapper.updateById(snapshot);

        return snapshot;
    }

    /**
     * F-14 — attach uploaded bytes to pending entries.
     *
     * <p>Run this AFTER uploading each pending file through the normal
     * asset upload API (which returns the asset id and its server-
     * computed sha256). For every binding: the entry must exist in this
     * snapshot with status ADDED/CHANGED, the asset must belong to the
     * SAME (space, source) (anti-IDOR; never looked up by path), and
     * {@code asset.sha256 == entry.sha256} — a mismatch is a 409: the
     * bytes the client hashed are not the bytes it uploaded.
     *
     * <p>Transitions: ADDED/CHANGED → LINKED (via a status-guarded
     * UPDATE, so a double bind updates 0 rows and is reported, never
     * silently overwriting).
     *
     * @return the updated snapshot (pendingFiles recomputed; status
     *         stays AWAITING_UPLOAD until {@link #completeSync} confirms
     *         zero pending, so completion remains an explicit step)
     */
    @Transactional
    public FolderImportSnapshot bindEntries(String ownerSubject, Long spaceId, Long sourceId,
                                            Long snapshotId, List<FolderEntryBinding> bindings) {
        FolderImportSnapshot snapshot = requireOwnedSnapshot(ownerSubject, spaceId, sourceId, snapshotId);
        if (!STATUS_AWAITING_UPLOAD.equals(snapshot.getStatus())) {
            throw ApiException.conflict(ApiErrorCodes.FOLDER_SNAPSHOT_NOT_AWAITING_UPLOADS,
                    "snapshot is not awaiting uploads: " + snapshot.getStatus());
        }
        if (bindings == null || bindings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bindings must not be empty");
        }

        Map<String, FolderImportEntry> entriesByPath = new HashMap<>();
        for (FolderImportEntry entry : entryMapper.selectBySnapshotId(snapshotId)) {
            entriesByPath.put(entry.getRelativePath(), entry);
        }

        for (FolderEntryBinding binding : bindings) {
            validateRelativePath(binding.relativePath());
            FolderImportEntry entry = entriesByPath.get(binding.relativePath());
            if (entry == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unknown relativePath for this snapshot: " + binding.relativePath());
            }
            if (!ENTRY_ADDED.equals(entry.getStatus()) && !ENTRY_CHANGED.equals(entry.getStatus())) {
                if (ENTRY_LINKED.equals(entry.getStatus())) {
                    // Idempotent re-bind of the SAME asset: fine; a
                    // different asset on an already-bound entry is 409.
                    if (!entry.getSourceAssetId().equals(binding.assetId())) {
                        throw ApiException.conflict(ApiErrorCodes.FOLDER_ENTRY_ALREADY_LINKED,
                                "entry already linked to a different asset: " + entry.getRelativePath());
                    }
                    continue;
                }
                throw ApiException.conflict(ApiErrorCodes.FOLDER_ENTRY_NOT_PENDING,
                        "entry is not pending: " + entry.getRelativePath() + " [" + entry.getStatus() + "]");
            }
            // Server-side ownership + integrity: the asset must live in
            // this exact (space, source) and match the manifest hash.
            SourceAsset asset = sourceAssetMapper.selectByIdSpaceSource(
                    binding.assetId(), spaceId, sourceId);
            if (asset == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "asset not found in this space/source: " + binding.assetId());
            }
            if (entry.getSha256() != null && !entry.getSha256().equalsIgnoreCase(asset.getSha256())) {
                throw ApiException.conflict(ApiErrorCodes.FOLDER_ENTRY_CHECKSUM_MISMATCH,
                        "sha256 mismatch for " + entry.getRelativePath()
                                + ": manifest says " + entry.getSha256()
                                + ", uploaded bytes are " + asset.getSha256());
            }
            int updated = entryMapper.bindPendingEntry(entry.getId(), ENTRY_LINKED, asset.getId());
            if (updated == 0) {
                throw ApiException.conflict(ApiErrorCodes.FOLDER_ENTRY_STALE,
                        "entry changed concurrently, re-submit: " + entry.getRelativePath());
            }
        }

        snapshot.setPendingFiles(countPendingOf(entryMapper.selectBySnapshotId(snapshotId)));
        snapshotMapper.updateById(snapshot);
        return snapshot;
    }

    /**
     * F-14 — resync completion path.
     *
     * <p>When every ADDED/CHANGED entry is bound, flips the snapshot to
     * COMPLETED (the real chain end, replacing the old immediate flip).
     * When entries are still pending, the snapshot stays
     * AWAITING_UPLOAD and the response lists exactly what the client
     * must still upload — a 200 with {@code completed=false}, never a
     * fake success.
     */
    @Transactional
    public FolderSyncCompletion completeSync(String ownerSubject, Long spaceId, Long sourceId,
                                             Long snapshotId) {
        FolderImportSnapshot snapshot = requireOwnedSnapshot(ownerSubject, spaceId, sourceId, snapshotId);
        if (STATUS_COMPLETED.equals(snapshot.getStatus())) {
            return new FolderSyncCompletion(true, STATUS_COMPLETED, snapshot.getPendingFiles() == null
                    ? 0 : snapshot.getPendingFiles(), List.of(), snapshot);
        }
        if (!STATUS_AWAITING_UPLOAD.equals(snapshot.getStatus())) {
            throw ApiException.conflict(ApiErrorCodes.FOLDER_SNAPSHOT_NOT_AWAITING_UPLOADS,
                    "snapshot is not in the upload chain: " + snapshot.getStatus());
        }
        List<FolderImportEntry> pending = new ArrayList<>();
        for (FolderImportEntry entry : entryMapper.selectBySnapshotId(snapshotId)) {
            if (ENTRY_ADDED.equals(entry.getStatus()) || ENTRY_CHANGED.equals(entry.getStatus())) {
                pending.add(entry);
            }
        }
        if (pending.isEmpty()) {
            snapshot.setPendingFiles(0);
            snapshot.setStatus(STATUS_COMPLETED);
            snapshotMapper.updateById(snapshot);
            return new FolderSyncCompletion(true, STATUS_COMPLETED, 0, List.of(), snapshot);
        }
        snapshot.setPendingFiles(pending.size());
        snapshotMapper.updateById(snapshot);
        return new FolderSyncCompletion(false, snapshot.getStatus(), pending.size(),
                List.copyOf(pending), snapshot);
    }

    public List<FolderImportSnapshot> listSnapshots(String ownerSubject, Long spaceId, Long sourceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return new ArrayList<>();
        }
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return new ArrayList<>();
        }
        return snapshotMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

    public FolderImportSnapshot getSnapshot(String ownerSubject, Long spaceId, Long sourceId, Long snapshotId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        return snapshotMapper.selectByIdAndSpace(snapshotId, spaceId, sourceId, ownerSubject);
    }

    public List<FolderImportEntry> getEntries(String ownerSubject, Long spaceId, Long sourceId, Long snapshotId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return new ArrayList<>();
        }
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return new ArrayList<>();
        }
        return entryMapper.selectBySnapshotAndSpace(snapshotId, spaceId, sourceId, ownerSubject);
    }

    /**
     * Delete a folder-sync snapshot owned by the caller.
     * Only removes the snapshot and its entries; does not delete SourceAssets
     * that may already have been created by a later ingestion of those files.
     */
    @Transactional
    public void deleteSnapshot(String ownerSubject, Long spaceId, Long sourceId, Long snapshotId) {
        FolderImportSnapshot snapshot = getSnapshot(ownerSubject, spaceId, sourceId, snapshotId);
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "snapshot not found");
        }
        entryMapper.deleteBySnapshotId(snapshotId);
        snapshotMapper.deleteById(snapshotId);
    }

    // ==================== helpers ====================

    private FolderImportSnapshot requireOwnedSnapshot(String ownerSubject, Long spaceId,
                                                      Long sourceId, Long snapshotId) {
        FolderImportSnapshot snapshot = getSnapshot(ownerSubject, spaceId, sourceId, snapshotId);
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "snapshot not found");
        }
        return snapshot;
    }

    private static int countPendingOf(List<FolderImportEntry> entries) {
        int pending = 0;
        for (FolderImportEntry entry : entries) {
            if (ENTRY_ADDED.equals(entry.getStatus()) || ENTRY_CHANGED.equals(entry.getStatus())) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * Trust-boundary check: a manifest path must be a clean RELATIVE
     * POSIX path. Rejects null/blank, absolute (/ or \), drive-prefixed
     * ({@code C:}), backslashes, {@code .}/{@code ..} segments and
     * over-512-char paths. The backend never resolves or stores a disk
     * location; relativePath is compared verbatim.
     */
    static void validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "relativePath must not be blank");
        }
        if (relativePath.length() > MAX_RELATIVE_PATH_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "relativePath must not exceed " + MAX_RELATIVE_PATH_LENGTH + " characters");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "relativePath must be relative");
        }
        if (relativePath.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "relativePath must use forward slashes");
        }
        if (relativePath.length() >= 2 && Character.isLetter(relativePath.charAt(0))
                && relativePath.charAt(1) == ':') {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "relativePath must not be drive-qualified");
        }
        for (String segment : relativePath.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "relativePath must not contain '.' or '..' segments");
            }
        }
    }

    /** 64 lowercase-or-uppercase hex characters (client-computed digest). */
    static void validateSha256(String sha256) {
        if (sha256 == null || sha256.length() != 64
                || !sha256.chars().allMatch(c -> Character.isDigit(c)
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sha256 must be 64 hex characters");
        }
    }

    /** One manifest line as submitted by the desktop client. */
    public record FolderImportEntryRequest(String relativePath, String sha256) {
    }

    /**
     * One upload-binding instruction: attach {@code assetId} (from the
     * asset upload response) to the pending entry at
     * {@code relativePath}. Both sides are validated server-side
     * (ownership + sha equality) before the LINKED transition.
     */
    public record FolderEntryBinding(String relativePath, Long assetId) {
    }

    /**
     * F-14 — typed result of {@link #completeSync}.
     *
     * @param completed      true only when nothing is pending anymore
     * @param status         snapshot status after the attempt
     * @param pendingCount   number of entries still needing bytes
     * @param pendingEntries the pending entries (path + expected sha) —
     *                       the client's to-upload list
     * @param snapshot       the snapshot itself (counts)
     */
    public record FolderSyncCompletion(boolean completed, String status, int pendingCount,
                                       List<FolderImportEntry> pendingEntries,
                                       FolderImportSnapshot snapshot) {
    }
}
