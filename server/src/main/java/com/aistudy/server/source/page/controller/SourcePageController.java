package com.aistudy.server.source.page.controller;

import com.aistudy.server.source.page.dto.ReorderPagesRequest;
import com.aistudy.server.source.page.dto.SourcePageResponse;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.service.SourcePageService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Source page list + human reorder.
 * Reorder uses a typed DTO; path sourceId is the ownership source of truth.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/pages")
@SecurityRequirement(name = "bearerAuth")
public class SourcePageController {

    private final SourcePageService sourcePageService;

    public SourcePageController(SourcePageService sourcePageService) {
        this.sourcePageService = sourcePageService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SourcePageResponse> list(@PathVariable Long spaceId,
                                         @PathVariable Long sourceId,
                                         Authentication authentication) {
        List<SourcePage> pages = sourcePageService.listMine(authentication.getName(), spaceId, sourceId);
        if (pages == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        // The entity must not reach the wire: it carried extractionRevisionId,
        // an internal revision pointer, and springdoc published that as the contract.
        return pages.stream().map(SourcePageResponse::from).toList();
    }

    @PostMapping(value = "/batch-reorder",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void batchReorder(@PathVariable Long spaceId,
                             @PathVariable Long sourceId,
                             @RequestBody ReorderPagesRequest request,
                             Authentication authentication) {
        sourcePageService.batchUpdateOrder(authentication.getName(), spaceId, sourceId, request);
    }
}
