package com.aistudy.server.ingestion.job.controller;

import com.aistudy.server.ingestion.job.dto.CreateIngestionJobRequest;
import com.aistudy.server.ingestion.job.dto.IngestionJobResponse;
import com.aistudy.server.ingestion.job.entity.IngestionJob;
import com.aistudy.server.ingestion.job.service.IngestionJobService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * BUSINESS-005 — production IngestionJob REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs
 *       → 201, body {assetId}; job PENDING, or FAILED when the ZIP
 *       safety gate rejects the asset
 *   GET  /api/v1/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs
 *       → 200, job history for my source (newest first)
 *   GET  /api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}
 *       → 200, one job (space-scoped, api-guidelines.md §6); 404 if
 *       absent / not mine
 *   POST /api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}/retry
 *       → 200, FAILED → PENDING (retryCount++); 409 if not FAILED
 * </pre>
 *
 * <p>All endpoints are owner/space scoped: 404 (not 403) uniformly
 * expresses "absent or not yours" (api-guidelines.md §13); retry on a
 * non-FAILED job is 409 (state conflict). CSRF: the existing
 * {@code /api/v1/spaces/**} Bearer ignore already covers these nested
 * paths — no SecurityConfig change.
 */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class IngestionJobController {

    private final IngestionJobService ingestionJobService;

    public IngestionJobController(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    /**
     * Creates one ingestion job for the caller's own source + asset.
     * The V1 ZIP safety gate runs synchronously: an unsafe or
     * unreadable ZIP asset returns a FAILED job with
     * {@code errorCode=ZIP_SAFETY_VIOLATION} and a safe message.
     */
    @PostMapping(value = "/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionJobResponse create(@PathVariable Long spaceId,
                                       @PathVariable Long sourceId,
                                       @Valid @RequestBody CreateIngestionJobRequest request,
                                       Authentication authentication) {
        IngestionJob created = ingestionJobService.create(
                authentication.getName(), spaceId, sourceId, request.assetId());
        if (created == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source or SourceAsset not found");
        }
        return IngestionJobResponse.from(created);
    }

    /**
     * Lists the job history of the caller's own source, newest first.
     * 404 when the source is absent or not owned; 200 with an empty
     * list when owned but no jobs.
     */
    @GetMapping(value = "/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<IngestionJobResponse> list(@PathVariable Long spaceId,
                                           @PathVariable Long sourceId,
                                           Authentication authentication) {
        List<IngestionJob> jobs = ingestionJobService.listMine(
                authentication.getName(), spaceId, sourceId);
        if (jobs == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source not found");
        }
        return jobs.stream()
                .map(IngestionJobResponse::from)
                .toList();
    }

    /**
     * Returns ONE job of the caller's own space (space-scoped path,
     * api-guidelines.md §6). 404 when the job is absent, its source
     * lives in another space, or the space is not owned.
     */
    @GetMapping(value = "/spaces/{spaceId}/ingestion-jobs/{jobId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IngestionJobResponse get(@PathVariable Long spaceId,
                                    @PathVariable Long jobId,
                                    Authentication authentication) {
        IngestionJob job = ingestionJobService.getMine(
                authentication.getName(), spaceId, jobId);
        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "IngestionJob not found");
        }
        return IngestionJobResponse.from(job);
    }

    /**
     * Retries a FAILED job (R-INGEST-010): FAILED → PENDING with
     * {@code retryCount++}; the V1 ZIP safety gate re-runs. 409 when
     * the job is not in FAILED state.
     */
    @PostMapping(value = "/spaces/{spaceId}/ingestion-jobs/{jobId}/retry",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IngestionJobResponse retry(@PathVariable Long spaceId,
                                      @PathVariable Long jobId,
                                      Authentication authentication) {
        IngestionJob job = ingestionJobService.retry(
                authentication.getName(), spaceId, jobId);
        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "IngestionJob not found");
        }
        return IngestionJobResponse.from(job);
    }
}
