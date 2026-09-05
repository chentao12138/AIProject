package com.aistudy.server.knowledge.point.controller;

import com.aistudy.server.knowledge.point.dto.CreateKnowledgePointRequest;
import com.aistudy.server.knowledge.point.dto.KnowledgePointResponse;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.service.KnowledgePointService;
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
 * BUSINESS-003 — production KnowledgePoint REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/knowledge-points
 *       → 201, create USER_CURATED DRAFT
 *   GET  /api/v1/spaces/{spaceId}/knowledge-points
 *       → 200 typed List (non-deleted, newest first)
 *   GET  /api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}
 *       → 200 / 404
 *   POST /api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish
 *       → 200 (DRAFT → PUBLISHED; idempotent re-publish), no body
 * </pre>
 *
 * <p>All endpoints require Bearer authentication (class-level
 * {@code @SecurityRequirement(name = "bearerAuth")}); runtime
 * enforcement from the existing SecurityFilterChain. CSRF: the
 * existing path-scoped ignore {@code /api/v1/spaces/**} already
 * covers these nested routes — no SecurityConfig change.
 *
 * <p>404 uniformly expresses absent/not-owned/category-invalid
 * (anti-probing), consistent with BUSINESS-001/002.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/knowledge-points")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgePointController {

    private final KnowledgePointService knowledgePointService;

    public KnowledgePointController(KnowledgePointService knowledgePointService) {
        this.knowledgePointService = knowledgePointService;
    }

    /**
     * Creates a USER_CURATED DRAFT point in the caller's own space.
     * Server sets originType/status/createdByUserId/publishedAt/
     * deletedAt — none are accepted from the client.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgePointResponse create(@PathVariable Long spaceId,
                                         @Valid @RequestBody CreateKnowledgePointRequest request,
                                         Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint created = knowledgePointService.create(ownerSubject, spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "LearningSpace or KnowledgeCategory not found");
        }
        return KnowledgePointResponse.from(created);
    }

    /**
     * Lists the caller's own space's non-deleted points, newest
     * first. 404 when the space is not owned.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KnowledgePointResponse> list(@PathVariable Long spaceId,
                                             Authentication authentication) {
        String ownerSubject = authentication.getName();
        List<KnowledgePoint> points = knowledgePointService.listMine(ownerSubject, spaceId);
        if (points == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "LearningSpace not found");
        }
        return points.stream()
                .map(KnowledgePointResponse::from)
                .toList();
    }

    /**
     * Returns ONE non-deleted point of the caller's own space.
     * 404 when absent / other space / other owner (owner-scoped SQL).
     */
    @GetMapping(value = "/{knowledgePointId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgePointResponse get(@PathVariable Long spaceId,
                                      @PathVariable Long knowledgePointId,
                                      Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint point = knowledgePointService.getMine(ownerSubject, spaceId, knowledgePointId);
        if (point == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "KnowledgePoint not found");
        }
        return KnowledgePointResponse.from(point);
    }

    /**
     * Publishes a point (DRAFT → PUBLISHED). No request body.
     * 200 with the refreshed point; idempotent for already-published
     * points; 404 when absent / not owned.
     */
    @PostMapping(value = "/{knowledgePointId}/publish",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgePointResponse publish(@PathVariable Long spaceId,
                                          @PathVariable Long knowledgePointId,
                                          Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint point = knowledgePointService.publish(ownerSubject, spaceId, knowledgePointId);
        if (point == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "KnowledgePoint not found");
        }
        return KnowledgePointResponse.from(point);
    }
}
