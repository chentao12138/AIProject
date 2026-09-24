package com.aistudy.server.source.folder.service;

import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.folder.entity.FolderImportEntry;
import com.aistudy.server.source.folder.entity.FolderImportSnapshot;
import com.aistudy.server.source.folder.mapper.FolderImportEntryMapper;
import com.aistudy.server.source.folder.mapper.FolderImportSnapshotMapper;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FolderImportService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String ENTRY_UNCHANGED = "UNCHANGED";
    public static final String ENTRY_ADDED = "ADDED";
    public static final String ENTRY_REMOVED = "REMOVED";
    public static final String ENTRY_MISSING = "MISSING";

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
        snapshot.setRemovedFiles(0);
        snapshot.setStatus(STATUS_DRAFT);
        snapshot.setCreatedAt(now);
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "snapshot already processed");
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int unchanged = 0;
        int added = 0;

        for (FolderImportEntryRequest entryReq : entries) {
            FolderImportEntry entry = new FolderImportEntry();
            entry.setSnapshotId(snapshotId);
            entry.setRelativePath(entryReq.relativePath());
            entry.setSha256(entryReq.sha256());
            entry.setCreatedAt(now);

            SourceAsset existing = sourceAssetMapper.selectBySpaceAndSha256(spaceId, entryReq.sha256(), ownerSubject);
            if (existing != null) {
                entry.setStatus(ENTRY_UNCHANGED);
                entry.setSourceAssetId(existing.getId());
                unchanged++;
            } else {
                entry.setStatus(ENTRY_ADDED);
                entry.setSourceAssetId(null);
                added++;
            }
            entryMapper.insert(entry);
        }

        snapshot.setTotalFiles(entries.size());
        snapshot.setUnchangedFiles(unchanged);
        snapshot.setAddedFiles(added);
        snapshot.setRemovedFiles(0);
        snapshot.setStatus(STATUS_COMPLETED);
        snapshotMapper.updateById(snapshot);

        return snapshot;
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

    public record FolderImportEntryRequest(String relativePath, String sha256) {
    }
}
