package com.aistudy.server.source.content.controller;

import com.aistudy.server.source.content.dto.ContentBlockResponse;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.service.ContentBlockService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * BUSINESS-006 — production ContentBlock read API.
 *
 * <pre>
 *   GET /api/v1/spaces/{spaceId}/sources/{sourceId}/content-blocks
 *       → 200, blocks of my source in document order
 *   GET .../content-blocks?pageId={pageId}
 *       → 200, blocks of one page of my source
 * </pre>
 *
 * <p>{@code pageId} is a query filter: a page id from another source
 * yields an empty list (no leak). 404 (not 403) uniformly expresses
 * "absent or not yours" for the source (api-guidelines.md §13). CSRF:
 * the existing {@code /api/v1/spaces/**} Bearer ignore already covers
 * these nested paths — no SecurityConfig change.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/content-blocks")
@SecurityRequirement(name = "bearerAuth")
public class ContentBlockController {

    private final ContentBlockService contentBlockService;

    public ContentBlockController(ContentBlockService contentBlockService) {
        this.contentBlockService = contentBlockService;
    }

    /**
     * Lists the blocks of the caller's own source in document order,
     * optionally scoped to one page of that source. 404 when the
     * source is absent or not owned; 200 with an empty list when
     * owned but no blocks.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ContentBlockResponse> list(@PathVariable Long spaceId,
                                           @PathVariable Long sourceId,
                                           @RequestParam(name = "pageId", required = false) Long pageId,
                                           Authentication authentication) {
        List<ContentBlock> blocks = contentBlockService.listMine(
                authentication.getName(), spaceId, sourceId, pageId);
        if (blocks == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source not found");
        }
        return blocks.stream()
                .map(ContentBlockResponse::from)
                .toList();
    }
}
