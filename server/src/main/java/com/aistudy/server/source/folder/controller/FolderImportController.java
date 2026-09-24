package com.aistudy.server.source.folder.controller;

import com.aistudy.server.source.folder.entity.FolderImportEntry;
import com.aistudy.server.source.folder.entity.FolderImportSnapshot;
import com.aistudy.server.source.folder.service.FolderImportService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Desktop folder-sync backend protocol.
 *
 * <p>Identity always comes from the JWT subject. Clients never pass userId.
 * Snapshot delete removes DRAFT/COMPLETED snapshots owned by the caller;
 * linked SourceAsset rows created by ingestion are not deleted here.
 */
@RestController
@SecurityRequirement(name = "bearerAuth")
public class FolderImportController {

    private final FolderImportService folderImportService;

    public FolderImportController(FolderImportService folderImportService) {
        this.folderImportService = folderImportService;
    }

    @PostMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/folder-sync/snapshot")
    @ResponseStatus(HttpStatus.CREATED)
    public FolderImportSnapshot createSnapshot(@PathVariable Long spaceId,
                                               @PathVariable Long sourceId,
                                               Authentication authentication) {
        FolderImportSnapshot created = folderImportService.createSnapshot(
                authentication.getName(), spaceId, sourceId);
        if (created == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "LearningSpace or Source not found");
        }
        return created;
    }

    @PostMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/folder-sync/snapshot/{snapshotId}/entries")
    public FolderImportSnapshot submitEntries(@PathVariable Long spaceId,
                                              @PathVariable Long sourceId,
                                              @PathVariable Long snapshotId,
                                              @RequestBody List<FolderImportService.FolderImportEntryRequest> entries,
                                              Authentication authentication) {
        return folderImportService.submitEntries(
                authentication.getName(), spaceId, sourceId, snapshotId, entries);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/folder-sync/snapshot")
    public List<FolderImportSnapshot> listSnapshots(@PathVariable Long spaceId,
                                                    @PathVariable Long sourceId,
                                                    Authentication authentication) {
        return folderImportService.listSnapshots(authentication.getName(), spaceId, sourceId);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/folder-sync/snapshot/{snapshotId}")
    public FolderImportSnapshot getSnapshot(@PathVariable Long spaceId,
                                            @PathVariable Long sourceId,
                                            @PathVariable Long snapshotId,
                                            Authentication authentication) {
        FolderImportSnapshot snapshot = folderImportService.getSnapshot(
                authentication.getName(), spaceId, sourceId, snapshotId);
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "snapshot not found");
        }
        return snapshot;
    }

    @GetMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/folder-sync/snapshot/{snapshotId}/entries")
    public List<FolderImportEntry> listEntries(@PathVariable Long spaceId,
                                               @PathVariable Long sourceId,
                                               @PathVariable Long snapshotId,
                                               Authentication authentication) {
        return folderImportService.getEntries(
                authentication.getName(), spaceId, sourceId, snapshotId);
    }

    @DeleteMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/folder-sync/snapshot/{snapshotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSnapshot(@PathVariable Long spaceId,
                               @PathVariable Long sourceId,
                               @PathVariable Long snapshotId,
                               Authentication authentication) {
        folderImportService.deleteSnapshot(
                authentication.getName(), spaceId, sourceId, snapshotId);
    }
}
