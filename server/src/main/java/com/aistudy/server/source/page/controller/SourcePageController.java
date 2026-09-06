package com.aistudy.server.source.page.controller;

import com.aistudy.server.source.page.dto.SourcePageResponse;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.service.SourcePageService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * BUSINESS-006 — production SourcePage read API.
 *
 * <pre>
 *   GET /api/v1/spaces/{spaceId}/sources/{sourceId}/pages
 *       → 200, pages of my source in final reading order
 * </pre>
 *
 * <p>404 (not 403) uniformly expresses "absent or not yours"
 * (api-guidelines.md §13). CSRF: the existing
 * {@code /api/v1/spaces/**} Bearer ignore already covers these nested
 * paths — no SecurityConfig change.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/pages")
@SecurityRequirement(name = "bearerAuth")
public class SourcePageController {

    private final SourcePageService sourcePageService;

    public SourcePageController(SourcePageService sourcePageService) {
        this.sourcePageService = sourcePageService;
    }

    /**
     * Lists the pages of the caller's own source in final reading
     * order. 404 when the source is absent or not owned; 200 with an
     * empty list when owned but no pages.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SourcePageResponse> list(@PathVariable Long spaceId,
                                         @PathVariable Long sourceId,
                                         Authentication authentication) {
        List<SourcePage> pages = sourcePageService.listMine(
                authentication.getName(), spaceId, sourceId);
        if (pages == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source not found");
        }
        return pages.stream()
                .map(SourcePageResponse::from)
                .toList();
    }
}
