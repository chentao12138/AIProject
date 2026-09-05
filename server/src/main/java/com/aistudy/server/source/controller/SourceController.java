package com.aistudy.server.source.controller;

import com.aistudy.server.source.dto.CreateSourceRequest;
import com.aistudy.server.source.dto.SourceResponse;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
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
 * BUSINESS-002 — production Source REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/sources            → 201, register metadata
 *   GET  /api/v1/spaces/{spaceId}/sources            → 200, list my space's sources
 *   GET  /api/v1/spaces/{spaceId}/sources/{sourceId} → 200, get one; 404 if absent/not mine
 * </pre>
 *
 * <p>Paths follow api-guidelines.md §2. All endpoints require Bearer
 * authentication (class-level
 * {@code @SecurityRequirement(name = "bearerAuth")}; runtime
 * enforcement comes from the existing SecurityFilterChain —
 * {@code anyRequest().authenticated()} + JWT Resource Server).
 *
 * <h3>Authorization</h3>
 *
 * <p>Source ownership is derived from the parent LearningSpace:
 *
 * <ul>
 *   <li>create/list: the service first proves {@code spaceId}
 *       belongs to {@code authentication.getName()} via the
 *       BUSINESS-001 owner-scoped parent query; otherwise 404.</li>
 *   <li>get: a single JOIN query constrains
 *       {@code source.id + source.space_id + learning_space.owner_subject}
 *       in SQL — a correct owner's spaceId combined with a
 *       sourceId from ANOTHER space returns 404 too (anti-IDOR).</li>
 * </ul>
 *
 * <p>404 (not 403) uniformly expresses "absent or not yours",
 * matching the BUSINESS-001 convention (api-guidelines.md §13).
 *
 * <h3>Typed DTOs, no Map returns</h3>
 *
 * <p>Records ({@link CreateSourceRequest}, {@link SourceResponse})
 * give springdoc explicit schemas in {@code /v3/api-docs}.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources")
@SecurityRequirement(name = "bearerAuth")
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    /**
     * Registers a Source (metadata only) inside the caller's own
     * LearningSpace. 404 when the parent space is absent or not
     * owned; 400 when the body fails validation.
     *
     * @param spaceId        parent space id from the path
     * @param request        validated body (title + sourceType)
     * @param authentication Spring Security context
     * @return 201 + typed source response
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SourceResponse create(@PathVariable Long spaceId,
                                 @Valid @RequestBody CreateSourceRequest request,
                                 Authentication authentication) {
        String ownerSubject = authentication.getName();
        Source created = sourceService.create(ownerSubject, spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "LearningSpace not found");
        }
        return SourceResponse.from(created);
    }

    /**
     * Lists the caller's own space's sources, newest first.
     * 404 when the parent space is absent or not owned; 200 with an
     * empty list when the space is owned but has no sources.
     *
     * @param spaceId        parent space id from the path
     * @param authentication Spring Security context
     * @return 200 + typed source list
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SourceResponse> list(@PathVariable Long spaceId,
                                     Authentication authentication) {
        String ownerSubject = authentication.getName();
        List<Source> sources = sourceService.listMine(ownerSubject, spaceId);
        if (sources == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "LearningSpace not found");
        }
        return sources.stream()
                .map(SourceResponse::from)
                .toList();
    }

    /**
     * Returns ONE of the caller's own space's sources.
     * 404 when the source is absent, belongs to a different space,
     * or the parent space is not owned — the JOIN collapses all
     * three into "no row".
     *
     * @param spaceId        parent space id from the path
     * @param sourceId       source id from the path
     * @param authentication Spring Security context
     * @return 200 + typed source response
     */
    @GetMapping(value = "/{sourceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SourceResponse get(@PathVariable Long spaceId,
                              @PathVariable Long sourceId,
                              Authentication authentication) {
        String ownerSubject = authentication.getName();
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source not found");
        }
        return SourceResponse.from(source);
    }
}
