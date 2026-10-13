package com.aistudy.server.source.asset.controller;

import com.aistudy.server.source.asset.dto.DuplicateCheckResponse;
import com.aistudy.server.source.asset.dto.SourceAssetResponse;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.service.SourceAssetService;
import com.aistudy.server.storage.RangeHeaderParser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
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
     * <p>F-13 adds an OPTIONAL multipart form field
     * {@code duplicatePolicy} (case-insensitive {@code skip} /
     * {@code importAgain}). Omitting it keeps the legacy contract
     * exactly (bytes always stored, row always inserted, the response
     * only flags {@code isDuplicate}). With {@code skip}, a hash hit
     * in the same space reuses the existing asset and stores nothing
     * new ({@code uploadDecision=REUSED_EXISTING}); with
     * {@code importAgain} a hash hit creates an explicit second import
     * ({@code uploadDecision=IMPORTED_AGAIN}). Unknown values → 400.
     *
     * @param spaceId        parent space id from the path
     * @param sourceId       parent source id from the path
     * @param file           multipart part named "file"
     * @param duplicatePolicy optional multipart form field, may be null
     * @param authentication Spring Security context
     * @return 201 + typed asset response carrying the F-13 decision
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SourceAssetResponse upload(@PathVariable Long spaceId,
                                      @PathVariable Long sourceId,
                                      @RequestPart("file") MultipartFile file,
                                      @RequestParam(value = "duplicatePolicy", required = false)
                                      String duplicatePolicy,
                                      Authentication authentication) {
        SourceAssetService.DuplicatePolicy policy =
                SourceAssetService.DuplicatePolicy.fromRequestParam(duplicatePolicy);
        if (duplicatePolicy != null && !duplicatePolicy.isBlank() && policy == null) {
            // Literal code: ApiErrorCodes is another lane's file; the wire
            // status is 400/VALIDATION_ERROR via ResponseStatusException.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "duplicatePolicy must be 'skip' or 'importAgain'");
        }
        SourceAssetService.UploadOutcome outcome = sourceAssetService.uploadWithPolicy(
                authentication.getName(), spaceId, sourceId, file, policy);
        if (outcome == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source not found");
        }
        return SourceAssetResponse.from(
                outcome.asset(), outcome.duplicate(), outcome.decision(), outcome.duplicateOfId());
    }

    /**
     * F-13 — dry-run duplicate precheck keyed by (space, sha256)
     * (+ optional name for the SAME_NAME_DIFFERENT_HASH scenario).
     * Always 200 for owned spaces; the ANSWER is data, not an error.
     * 400 for a malformed sha256, 404 when the space is not the
     * caller's (the probe queries are owner-scoped and a foreign space
     * simply yields NONE — matching the anti-IDOR convention we return
     * NONE only for owned spaces via the space check below).
     */
    @GetMapping(value = "/duplicate-check", produces = MediaType.APPLICATION_JSON_VALUE)
    public DuplicateCheckResponse duplicateCheck(@PathVariable Long spaceId,
                                                 @PathVariable Long sourceId,
                                                 @RequestParam("sha256") String sha256,
                                                 @RequestParam(value = "name", required = false)
                                                 String name,
                                                 Authentication authentication) {
        // Ownership gate mirrors upload: an unknown/foreign (space, source)
        // pair must not even learn whether bytes exist.
        if (sourceAssetService.listMine(authentication.getName(), spaceId, sourceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        return DuplicateCheckResponse.from(
                sourceAssetService.precheckDuplicate(authentication.getName(), spaceId, sha256, name));
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
     * Authorized RAW download of one asset with explicit HTTP Range
     * support (F-15).
     *
     * <p>A controller-returned body never receives Spring's automatic
     * range handling, so {@code Range} is interpreted here via the
     * shared {@link RangeHeaderParser}:
     *
     * <ul>
     *   <li>no / invalid-but-unrecognized Range → 200 with the full
     *       body, {@code Accept-Ranges: bytes} and exact
     *       {@code Content-Length};</li>
     *   <li>satisfiable single range → 206 + {@code Content-Range} +
     *       exact {@code Content-Length}, streamed through a bounded
     *       {@link com.aistudy.server.storage.WindowInputStream} so a
     *       partial fetch never loads the whole file;</li>
     *   <li>unsatisfiable range (start past EOF, non-bytes unit,
     *       multi-range in V1) → 416 + {@code Content-Range: bytes
     *       * /total}.</li>
     * </ul>
     *
     * <p>The ownership gate is unchanged: {@code openRaw} runs the
     * same owner-scoped JOIN before any byte is touched.
     */
    @GetMapping(value = "/{assetId}/content")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadRaw(
            @PathVariable Long spaceId,
            @PathVariable Long sourceId,
            @PathVariable Long assetId,
            @RequestHeader(value = org.springframework.http.HttpHeaders.RANGE, required = false)
            String rangeHeader,
            Authentication authentication) {
        SourceAssetService.RawAsset raw = sourceAssetService.openRaw(
                authentication.getName(), spaceId, sourceId, assetId);
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SourceAsset not found");
        }
        SourceAsset asset = raw.asset();
        long totalSize = asset.getSizeBytes() == null ? 0L : asset.getSizeBytes();
        RangeHeaderParser.Result parsed = RangeHeaderParser.parse(rangeHeader, totalSize);
        String mime = asset.getMimeType() != null ? asset.getMimeType() : "application/octet-stream";
        String safeName = asset.getOriginalName() == null
                ? "asset-" + assetId
                : asset.getOriginalName().replaceAll("[\\r\\n\"\\\\]", "_");

        if (parsed.disposition() == RangeHeaderParser.Disposition.UNSATISFIABLE) {
            // The opened stream must not leak on the 416 path.
            closeQuietly(raw.stream());
            return org.springframework.http.ResponseEntity
                    .status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(org.springframework.http.HttpHeaders.CONTENT_RANGE,
                            RangeHeaderParser.unsatisfiableContentRange(totalSize))
                    .header(org.springframework.http.HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(null);
        }

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentDispositionFormData("attachment", safeName);
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(mime));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");

        org.springframework.core.io.Resource body;
        HttpStatus status;
        if (parsed.disposition() == RangeHeaderParser.Disposition.SATISFIED) {
            status = HttpStatus.PARTIAL_CONTENT;
            headers.set(HttpHeaders.CONTENT_RANGE, RangeHeaderParser.contentRange(parsed, totalSize));
            headers.setContentLength(parsed.length());
            body = new org.springframework.core.io.InputStreamResource(
                    new com.aistudy.server.storage.WindowInputStream(
                            raw.stream(), parsed.start(), parsed.length()),
                    asset.getOriginalName());
        } else {
            status = HttpStatus.OK;
            headers.setContentLength(totalSize);
            body = new org.springframework.core.io.InputStreamResource(raw.stream(),
                    asset.getOriginalName());
        }
        return org.springframework.http.ResponseEntity.status(status).headers(headers).body(body);
    }

    private static void closeQuietly(java.io.InputStream in) {
        try {
            in.close();
        } catch (java.io.IOException ignored) {
            // best-effort: nothing useful remains to do
        }
    }
}
