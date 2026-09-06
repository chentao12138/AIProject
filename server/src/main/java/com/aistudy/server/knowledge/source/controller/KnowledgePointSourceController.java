package com.aistudy.server.knowledge.source.controller;

import com.aistudy.server.knowledge.source.dto.KnowledgePointSourceResponse;
import com.aistudy.server.knowledge.source.dto.LinkKnowledgePointSourcesRequest;
import com.aistudy.server.knowledge.source.entity.KnowledgePointSource;
import com.aistudy.server.knowledge.source.service.KnowledgePointSourceService;
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
 * BUSINESS-007 — production KnowledgePointSource (provenance) REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/sources
 *       → 201, body {contentBlockIds:[...]}; links the blocks to the
 *       point (same-space enforced); already-linked pairs are no-ops;
 *       returns the point's full current link list
 *   GET  /api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/sources
 *       → 200, provenance links of my point (ordered by id)
 * </pre>
 *
 * <p>All endpoints are owner/space scoped: 404 (not 403) uniformly
 * expresses "absent or not yours" — including ANY invalid block id in
 * a batch (anti-probing, no partial insert) (api-guidelines.md §13).
 * CSRF: the existing {@code /api/v1/spaces/**} Bearer ignore already
 * covers these nested paths — no SecurityConfig change.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/sources")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgePointSourceController {

    private final KnowledgePointSourceService knowledgePointSourceService;

    public KnowledgePointSourceController(KnowledgePointSourceService knowledgePointSourceService) {
        this.knowledgePointSourceService = knowledgePointSourceService;
    }

    /**
     * Links ContentBlocks to the caller's own knowledge point
     * (same-space invariant enforced server-side). Returns the
     * point's full current link list (201).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<KnowledgePointSourceResponse> link(@PathVariable Long spaceId,
                                                   @PathVariable Long knowledgePointId,
                                                   @Valid @RequestBody LinkKnowledgePointSourcesRequest request,
                                                   Authentication authentication) {
        List<KnowledgePointSource> links = knowledgePointSourceService.link(
                authentication.getName(), spaceId, knowledgePointId,
                request.contentBlockIds());
        if (links == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "KnowledgePoint or ContentBlock not found");
        }
        return links.stream()
                .map(KnowledgePointSourceResponse::from)
                .toList();
    }

    /**
     * Lists the provenance links of the caller's own knowledge point.
     * 404 when the point is absent, not owned, or soft-deleted.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KnowledgePointSourceResponse> list(@PathVariable Long spaceId,
                                                   @PathVariable Long knowledgePointId,
                                                   Authentication authentication) {
        List<KnowledgePointSource> links = knowledgePointSourceService.listMine(
                authentication.getName(), spaceId, knowledgePointId);
        if (links == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "KnowledgePoint not found");
        }
        return links.stream()
                .map(KnowledgePointSourceResponse::from)
                .toList();
    }
}
