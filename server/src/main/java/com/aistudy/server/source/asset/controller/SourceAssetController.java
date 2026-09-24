package com.aistudy.server.source.asset.controller;

import com.aistudy.server.source.asset.dto.SourceAssetResponse;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.service.SourceAssetService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * BUSINESS-004 — production SourceAsset REST API (RAW upload + reads).
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/sources/{sourceId}/assets
 *       → 201, multipart/form-data, part name "file"
 *   GET  /api/v1/spaces/{spaceId}/sources/{sourceId}/assets
 *       → 200, list my source's assets (newest first)
 *   GET  /api/v1/spaces/{spaceId}/sources/{sourceId}/assets/{assetId}
 *       → 200, one asset; 404 if absent / not mine / wrong source
 * </pre>
 *
 * <p>The upload request accepts ONLY the multipart file part —
 * no JSON body fields (path / storageKey / sha256 / sizeBytes /
 * assetRole / spaceId / sourceId / ownerSubject are all server- or
 * path-derived). 404 (not 403) uniformly expresses "absent or not
 * yours" (api-guidelines.md §13). CSRF: the existing
 * {@code /api/v1/spaces/**} Bearer ignore already covers these
 * nested paths — no SecurityConfig change (BUSINESS-004 D3).
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/assets")
@SecurityRequirement(name = "bearerAuth")
public class SourceAssetController {

    private final SourceAssetService sourceAssetService;

    public SourceAssetController(SourceAssetService sourceAssetService) {
        this.sourceAssetService = sourceAssetService;
    }

    /**
     * Uploads one RAW file as an asset of the caller's own source.
     *
     * @param spaceId        parent space id from the path
     * @param sourceId       parent source id from the path
     * @param file           multipart part named "file"
     * @param authentication Spring Security context
     * @return 201 + typed asset response
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SourceAssetResponse upload(@PathVariable Long spaceId,
                                      @PathVariable Long sourceId,
                                      @RequestPart("file") MultipartFile file,
                                      Authentication authentication) {
        SourceAsset created = sourceAssetService.upload(
                authentication.getName(), spaceId, sourceId, file);
        if (created == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source not found");
        }
        SourceAsset duplicate = sourceAssetService.checkDuplicate(
                authentication.getName(), spaceId, sourceId, created.getSha256());
        boolean isDuplicate = duplicate != null && !duplicate.getId().equals(created.getId());
        return SourceAssetResponse.from(created, isDuplicate);
    }

    /**
     * Lists assets of the caller's own source, newest first.
     * 404 when the source is absent or not owned; 200 with an empty
     * list when owned but no assets.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SourceAssetResponse> list(@PathVariable Long spaceId,
                                          @PathVariable Long sourceId,
                                          Authentication authentication) {
        List<SourceAsset> assets = sourceAssetService.listMine(
                authentication.getName(), spaceId, sourceId);
        if (assets == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source not found");
        }
        return assets.stream()
                .map(SourceAssetResponse::from)
                .toList();
    }

    /**
     * Returns ONE asset of the caller's own source.
     * 404 when the asset is absent, belongs to a different source,
     * its source lives in a different space, or the space is not
     * owned — the single JOIN collapses all four into "no row".
     */
    @GetMapping(value = "/{assetId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SourceAssetResponse get(@PathVariable Long spaceId,
                                   @PathVariable Long sourceId,
                                   @PathVariable Long assetId,
                                   Authentication authentication) {
        SourceAsset asset = sourceAssetService.getMine(
                authentication.getName(), spaceId, sourceId, assetId);
        if (asset == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "SourceAsset not found");
        }
        return SourceAssetResponse.from(asset);
    }

    /**
     * Authorized RAW download of one asset.
     * Streams from storage; never returns storageKey.
     */
    @GetMapping(value = "/{assetId}/content")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadRaw(
            @PathVariable Long spaceId,
            @PathVariable Long sourceId,
            @PathVariable Long assetId,
            Authentication authentication) {
        SourceAssetService.RawAsset raw = sourceAssetService.openRaw(
                authentication.getName(), spaceId, sourceId, assetId);
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SourceAsset not found");
        }
        SourceAsset asset = raw.asset();
        org.springframework.core.io.InputStreamResource body =
                new org.springframework.core.io.InputStreamResource(raw.stream());
        String mime = asset.getMimeType() != null ? asset.getMimeType() : "application/octet-stream";
        String safeName = asset.getOriginalName() == null
                ? "asset-" + assetId
                : asset.getOriginalName().replaceAll("[\\r\\n\"\\\\]", "_");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(mime))
                .body(body);
    }
}
