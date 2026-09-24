package com.aistudy.server.knowledge.point.controller;

import com.aistudy.server.knowledge.point.dto.CreateKnowledgePointRequest;
import com.aistudy.server.knowledge.point.dto.KnowledgePointResponse;
import com.aistudy.server.knowledge.point.dto.UpdateKnowledgePointRequest;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.service.KnowledgePointService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/knowledge-points")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgePointController {

    private final KnowledgePointService knowledgePointService;

    public KnowledgePointController(KnowledgePointService knowledgePointService) {
        this.knowledgePointService = knowledgePointService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgePointResponse create(@PathVariable Long spaceId,
                                         @Valid @RequestBody CreateKnowledgePointRequest request,
                                         Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint created = knowledgePointService.create(ownerSubject, spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace or KnowledgeCategory not found");
        }
        return KnowledgePointResponse.from(created);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KnowledgePointResponse> list(@PathVariable Long spaceId,
                                             Authentication authentication) {
        String ownerSubject = authentication.getName();
        List<KnowledgePoint> points = knowledgePointService.listMine(ownerSubject, spaceId);
        if (points == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return points.stream().map(KnowledgePointResponse::from).toList();
    }

    @GetMapping(value = "/{knowledgePointId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgePointResponse get(@PathVariable Long spaceId,
                                      @PathVariable Long knowledgePointId,
                                      Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint point = knowledgePointService.getMine(ownerSubject, spaceId, knowledgePointId);
        if (point == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        return KnowledgePointResponse.from(point);
    }

    @PutMapping(value = "/{knowledgePointId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgePointResponse update(@PathVariable Long spaceId,
                                         @PathVariable Long knowledgePointId,
                                         @Valid @RequestBody UpdateKnowledgePointRequest request,
                                         Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint updated = knowledgePointService.update(ownerSubject, spaceId, knowledgePointId, request);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        return KnowledgePointResponse.from(updated);
    }

    @PostMapping(value = "/{knowledgePointId}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgePointResponse archive(@PathVariable Long spaceId,
                                          @PathVariable Long knowledgePointId,
                                          Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint updated = knowledgePointService.archive(ownerSubject, spaceId, knowledgePointId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        return KnowledgePointResponse.from(updated);
    }

    @PostMapping(value = "/{knowledgePointId}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgePointResponse restore(@PathVariable Long spaceId,
                                          @PathVariable Long knowledgePointId,
                                          Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint updated = knowledgePointService.restore(ownerSubject, spaceId, knowledgePointId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        return KnowledgePointResponse.from(updated);
    }

    @PostMapping(value = "/{knowledgePointId}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgePointResponse reject(@PathVariable Long spaceId,
                                         @PathVariable Long knowledgePointId,
                                         @Valid @RequestBody RejectKnowledgePointRequest request,
                                         Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint updated = knowledgePointService.reject(ownerSubject, spaceId, knowledgePointId, request.reason());
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        return KnowledgePointResponse.from(updated);
    }

    @PostMapping(value = "/{knowledgePointId}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgePointResponse publish(@PathVariable Long spaceId,
                                          @PathVariable Long knowledgePointId,
                                          Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgePoint point = knowledgePointService.publish(ownerSubject, spaceId, knowledgePointId);
        if (point == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgePoint not found");
        }
        return KnowledgePointResponse.from(point);
    }
}
