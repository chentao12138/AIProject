package com.aistudy.server.space.controller;

import com.aistudy.server.space.dto.CreateLearningSpaceRequest;
import com.aistudy.server.space.dto.LearningSpaceResponse;
import com.aistudy.server.space.entity.LearningSpace;
import com.aistudy.server.space.service.LearningSpaceService;
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
 * BUSINESS-001 — production LearningSpace REST API.
 *
 * <pre>
 *   POST /api/v1/spaces            → 201, create (owner = JWT sub)
 *   GET  /api/v1/spaces            → 200, list my spaces
 *   GET  /api/v1/spaces/{spaceId}  → 200, get my space; 404 if absent/not mine
 * </pre>
 *
 * <p>Paths follow api-guidelines.md §2. All endpoints require Bearer
 * authentication (class-level
 * {@code @SecurityRequirement(name = "bearerAuth")} declares it in
 * the OpenAPI contract; runtime enforcement comes from the existing
 * SecurityFilterChain — {@code anyRequest().authenticated()} plus the
 * JWT Resource Server — which already covers {@code /api/v1/**}).
 * The only security-config change BUSINESS-001 introduced is a
 * path-scoped CSRF exception for {@code /api/v1/spaces} and
 * {@code /api/v1/spaces/**} (these endpoints authenticate exclusively
 * via {@code Authorization: Bearer <JWT>}, never via cookies, so the
 * CSRF filter must not short-circuit anonymous POSTs into 403 before
 * the 401 authentication boundary; see
 * {@code SpikeSecurityConfig} BUSINESS-001-FIX-01).
 *
 * <h3>Owner boundary</h3>
 *
 * <p>Every method resolves the current user from the Spring Security
 * {@code Authentication} ({@code getName()} = JWT {@code sub}) and
 * passes it to the service, which routes all reads through
 * owner-scoped SQL. The client can never supply an owner.
 *
 * <h3>Not-found semantics</h3>
 *
 * <p>{@code GET /api/v1/spaces/{spaceId}} returns 404 when the space
 * does not exist OR belongs to another user (api-guidelines.md §13:
 * 404 = "不存在/按安全策略不可见"). The two cases are intentionally
 * indistinguishable to the caller — this prevents probing whether
 * another user's space exists. A future multi-owner authorization
 * feature may differentiate 403; the current owner-only model keeps
 * it 404.
 *
 * <h3>Typed DTOs, no Map returns</h3>
 *
 * <p>Request/response are Java records
 * ({@link CreateLearningSpaceRequest}, {@link LearningSpaceResponse}),
 * giving springdoc explicit schemas in {@code /v3/api-docs} and a
 * stable JSON contract for the future TypeScript client generation.
 * No {@code @Operation} / {@code @ApiResponse} / {@code @Schema}
 * annotations are stacked here — springdoc derives everything from
 * the Java types and the {@code produces} declarations.
 */
@RestController
@RequestMapping("/api/v1/spaces")
@SecurityRequirement(name = "bearerAuth")
public class LearningSpaceController {

    private final LearningSpaceService learningSpaceService;

    public LearningSpaceController(LearningSpaceService learningSpaceService) {
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * Creates a LearningSpace owned by the authenticated caller.
     *
     * <p>HTTP 201 with the created space (api-guidelines.md §13).
     * The owner is resolved server-side from the JWT subject — the
     * request body only carries {@code name} / {@code description}.
     *
     * @param request  validated body; {@code name} is {@code @NotBlank}
     * @param authentication Spring Security context for this request
     * @return 201 + typed space response
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LearningSpaceResponse create(@Valid @RequestBody CreateLearningSpaceRequest request,
                                        Authentication authentication) {
        String ownerSubject = authentication.getName();
        LearningSpace created = learningSpaceService.create(ownerSubject, request);
        return LearningSpaceResponse.from(created);
    }

    /**
     * Lists the authenticated caller's own spaces, newest first.
     * Never returns another subject's spaces — the SQL itself is
     * bounded by {@code owner_subject}.
     *
     * @param authentication Spring Security context for this request
     * @return 200 + typed list (empty list when the user has no spaces)
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<LearningSpaceResponse> list(Authentication authentication) {
        String ownerSubject = authentication.getName();
        return learningSpaceService.listMine(ownerSubject).stream()
                .map(LearningSpaceResponse::from)
                .toList();
    }

    /**
     * Returns ONE of the authenticated caller's spaces.
     *
     * <p>404 when the space does not exist OR belongs to a different
     * user — the mapper query ({@code id + owner_subject}) collapses
     * both cases into "no row", so the response is identical for
     * missing and foreign spaces.
     *
     * @param spaceId         space id from the path
     * @param authentication  Spring Security context for this request
     * @return 200 + typed space response
     * @throws ResponseStatusException 404 when absent or not owned
     */
    @GetMapping(value = "/{spaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LearningSpaceResponse get(@PathVariable Long spaceId,
                                     Authentication authentication) {
        String ownerSubject = authentication.getName();
        LearningSpace space = learningSpaceService.getMine(ownerSubject, spaceId);
        if (space == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "LearningSpace not found");
        }
        return LearningSpaceResponse.from(space);
    }
}
