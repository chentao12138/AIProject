package com.aistudy.server.source.controller;

import com.aistudy.server.source.dto.CreateSourceRequest;
import com.aistudy.server.source.dto.SourceResponse;
import com.aistudy.server.source.dto.UpdateSourceRequest;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources")
@SecurityRequirement(name = "bearerAuth")
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SourceResponse create(@PathVariable Long spaceId,
                                 @Valid @RequestBody CreateSourceRequest request,
                                 Authentication authentication) {
        String ownerSubject = authentication.getName();
        Source created = sourceService.create(ownerSubject, spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return SourceResponse.from(created);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SourceResponse> list(@PathVariable Long spaceId,
                                     Authentication authentication) {
        String ownerSubject = authentication.getName();
        List<Source> sources = sourceService.listMine(ownerSubject, spaceId);
        if (sources == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return sources.stream().map(SourceResponse::from).toList();
    }

    @GetMapping(value = "/{sourceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SourceResponse get(@PathVariable Long spaceId,
                              @PathVariable Long sourceId,
                              Authentication authentication) {
        String ownerSubject = authentication.getName();
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        return SourceResponse.from(source);
    }

    @PutMapping(value = "/{sourceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SourceResponse update(@PathVariable Long spaceId,
                                 @PathVariable Long sourceId,
                                 @Valid @RequestBody UpdateSourceRequest request,
                                 Authentication authentication) {
        String ownerSubject = authentication.getName();
        Source updated = sourceService.update(ownerSubject, spaceId, sourceId, request);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        return SourceResponse.from(updated);
    }

    @PostMapping(value = "/{sourceId}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public SourceResponse archive(@PathVariable Long spaceId,
                                  @PathVariable Long sourceId,
                                  Authentication authentication) {
        String ownerSubject = authentication.getName();
        Source updated = sourceService.archive(ownerSubject, spaceId, sourceId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        return SourceResponse.from(updated);
    }

    @PostMapping(value = "/{sourceId}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    public SourceResponse restore(@PathVariable Long spaceId,
                                  @PathVariable Long sourceId,
                                  Authentication authentication) {
        String ownerSubject = authentication.getName();
        Source updated = sourceService.restore(ownerSubject, spaceId, sourceId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        return SourceResponse.from(updated);
    }

    @PostMapping(value = "/{sourceId}/review", produces = MediaType.APPLICATION_JSON_VALUE)
    public SourceResponse review(@PathVariable Long spaceId,
                                 @PathVariable Long sourceId,
                                 @Valid @RequestBody ReviewSourceRequest request,
                                 Authentication authentication) {
        String ownerSubject = authentication.getName();
        Source updated = sourceService.review(ownerSubject, spaceId, sourceId, request.reviewStatus(), ownerSubject, request.rejectedReason());
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        return SourceResponse.from(updated);
    }
}
