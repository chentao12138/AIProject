package com.aistudy.server.admin.source.controller;

import com.aistudy.server.admin.source.service.AdminSourceIngestionService;
import com.aistudy.server.admin.source.service.AdminSourceIngestionService.RawAsset;
import com.aistudy.server.ingestion.issue.entity.IngestionIssue;
import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.storage.StorageService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ADMIN source / ingestion / OCR governance surface.
 *
 * <p>Transport only: every read, write and state transition is the owning
 * module's decision, reached through {@link AdminSourceIngestionService}
 * (architecture.md §4: "Controller 不承载核心业务规则").
 */
@RestController
@RequestMapping("/api/v1/admin/sources")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSourceIngestionController {

    private final AdminSourceIngestionService governance;
    private final StorageService storageService;

    public AdminSourceIngestionController(AdminSourceIngestionService governance,
                                          StorageService storageService) {
        this.governance = governance;
        this.storageService = storageService;
    }

    @GetMapping
    public List<Map<String, Object>> listSources(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long spaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return governance.listSources(q, spaceId, page, size);
    }

    @GetMapping("/{sourceId}")
    public Map<String, Object> sourceDetail(@PathVariable Long sourceId) {
        return governance.sourceDetail(sourceId);
    }

    @GetMapping("/{sourceId}/revisions")
    public List<ExtractionRevision> revisions(@PathVariable Long sourceId,
                                              @RequestParam Long spaceId) {
        return governance.revisions(spaceId, sourceId);
    }

    @GetMapping("/{sourceId}/jobs")
    public List<Map<String, Object>> jobs(@PathVariable Long sourceId,
                                          @RequestParam Long spaceId) {
        return governance.jobs(sourceId, spaceId);
    }

    @PostMapping("/jobs/{jobId}/retry")
    public Map<String, Object> retryJob(@PathVariable Long jobId) {
        return governance.retryJob(jobId);
    }

    @GetMapping("/{sourceId}/issues")
    public List<IngestionIssue> issues(@PathVariable Long sourceId,
                                       @RequestParam Long spaceId) {
        return governance.issues(sourceId, spaceId);
    }

    @GetMapping("/{sourceId}/pages")
    public List<SourcePage> pages(@PathVariable Long sourceId,
                                  @RequestParam Long spaceId) {
        return governance.pages(sourceId, spaceId);
    }

    @PostMapping("/pages/{pageId}/reorder")
    public Map<String, Object> reorderPage(@PathVariable Long pageId,
                                           @RequestParam Long spaceId,
                                           @RequestParam Integer pageOrder) {
        return governance.reorderPage(pageId, spaceId, pageOrder);
    }

    @PostMapping("/blocks/{blockId}/edit")
    public Map<String, Object> editBlock(@PathVariable Long blockId,
                                         @RequestParam Long spaceId,
                                         @RequestBody Map<String, Object> body) {
        String text = body.get("normalizedText") == null ? null : String.valueOf(body.get("normalizedText"));
        String blockType = body.get("blockType") == null ? null : String.valueOf(body.get("blockType"));
        return governance.editBlock(blockId, spaceId, text, blockType);
    }

    @GetMapping("/{sourceId}/raw/{assetId}")
    public ResponseEntity<InputStreamResource> raw(@PathVariable Long sourceId,
                                                   @PathVariable Long assetId,
                                                   @RequestParam Long spaceId) {
        RawAsset asset = governance.rawAsset(sourceId, assetId, spaceId);
        String name = asset.originalName() == null || asset.originalName().isBlank()
                ? "asset-" + assetId : asset.originalName();
        MediaType mediaType = asset.mimeType() == null || asset.mimeType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(asset.mimeType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + name.replaceAll("[\\r\\n\"\\\\]", "_") + "\"")
                .contentType(mediaType)
                .body(new InputStreamResource(storageService.load(asset.storageKey())));
    }
}
