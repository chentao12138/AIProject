package com.aistudy.server.ingestion.revision.controller;

import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.aistudy.server.ingestion.revision.service.ExtractionRevisionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/revisions")
@SecurityRequirement(name = "bearerAuth")
public class ExtractionRevisionController {

    private final ExtractionRevisionService extractionRevisionService;

    public ExtractionRevisionController(ExtractionRevisionService extractionRevisionService) {
        this.extractionRevisionService = extractionRevisionService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ExtractionRevision> list(@PathVariable Long spaceId,
                                         @PathVariable Long sourceId,
                                         Authentication authentication) {
        List<ExtractionRevision> revisions = extractionRevisionService.listMine(
                authentication.getName(), spaceId, sourceId);
        if (revisions == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return revisions;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExtractionRevision create(@PathVariable Long spaceId,
                                     @PathVariable Long sourceId,
                                     @RequestBody ExtractionRevision request,
                                     Authentication authentication) {
        ExtractionRevision created = extractionRevisionService.create(
                authentication.getName(), spaceId, sourceId, request);
        if (created == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace or Source not found");
        }
        return created;
    }
}
