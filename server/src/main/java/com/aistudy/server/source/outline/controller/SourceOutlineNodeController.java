package com.aistudy.server.source.outline.controller;

import com.aistudy.server.source.outline.dto.CreateOutlineNodeRequest;
import com.aistudy.server.source.outline.dto.UpdateOutlineNodeRequest;
import com.aistudy.server.source.outline.entity.SourceOutlineNode;
import com.aistudy.server.source.outline.service.SourceOutlineNodeService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Outline tree API. Path sourceId is ownership truth; typed DTOs only.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sources/{sourceId}/outline")
@SecurityRequirement(name = "bearerAuth")
public class SourceOutlineNodeController {

    private final SourceOutlineNodeService sourceOutlineNodeService;

    public SourceOutlineNodeController(SourceOutlineNodeService sourceOutlineNodeService) {
        this.sourceOutlineNodeService = sourceOutlineNodeService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SourceOutlineNode> list(@PathVariable Long spaceId,
                                        @PathVariable Long sourceId,
                                        Authentication authentication) {
        List<SourceOutlineNode> nodes = sourceOutlineNodeService.listMine(
                authentication.getName(), spaceId, sourceId);
        if (nodes == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Source not found");
        }
        return nodes;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SourceOutlineNode create(@PathVariable Long spaceId,
                                    @PathVariable Long sourceId,
                                    @RequestBody CreateOutlineNodeRequest request,
                                    Authentication authentication) {
        SourceOutlineNode created = sourceOutlineNodeService.create(
                authentication.getName(), spaceId, sourceId, request);
        if (created == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Source not found");
        }
        return created;
    }

    @PutMapping(value = "/{nodeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SourceOutlineNode update(@PathVariable Long spaceId,
                                    @PathVariable Long sourceId,
                                    @PathVariable Long nodeId,
                                    @RequestBody UpdateOutlineNodeRequest request,
                                    Authentication authentication) {
        return sourceOutlineNodeService.update(
                authentication.getName(), spaceId, sourceId, nodeId, request);
    }

    @DeleteMapping(value = "/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long spaceId,
                       @PathVariable Long sourceId,
                       @PathVariable Long nodeId,
                       Authentication authentication) {
        sourceOutlineNodeService.delete(authentication.getName(), spaceId, sourceId, nodeId);
    }
}
