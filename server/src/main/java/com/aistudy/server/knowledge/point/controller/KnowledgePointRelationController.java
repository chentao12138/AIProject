package com.aistudy.server.knowledge.point.controller;

import com.aistudy.server.knowledge.point.entity.KnowledgePointRelation;
import com.aistudy.server.knowledge.point.service.KnowledgePointRelationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class KnowledgePointRelationController {

    private final KnowledgePointRelationService knowledgePointRelationService;

    public KnowledgePointRelationController(KnowledgePointRelationService knowledgePointRelationService) {
        this.knowledgePointRelationService = knowledgePointRelationService;
    }

    public record AddRelationRequest(String relationType, Long targetKpId, Integer weight, String notes) {
    }

    @PostMapping("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/relations")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public KnowledgePointRelation add(@PathVariable Long spaceId,
                                      @PathVariable Long kpId,
                                      Authentication authentication,
                                      @RequestBody AddRelationRequest request) {
        KnowledgePointRelation created = knowledgePointRelationService.addRelation(
                authentication.getName(), spaceId, kpId, request.targetKpId(), request.relationType(),
                request.weight(), request.notes());
        if (created == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "LearningSpace or KnowledgePoint not found");
        }
        return created;
    }

    @GetMapping("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/relations")
    public List<KnowledgePointRelation> list(@PathVariable Long spaceId,
                                             @PathVariable Long kpId,
                                             Authentication authentication) {
        return knowledgePointRelationService.listRelations(authentication.getName(), spaceId, kpId);
    }

    @DeleteMapping("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/relations/{relationId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long spaceId,
                       @PathVariable Long kpId,
                       @PathVariable Long relationId,
                       Authentication authentication) {
        knowledgePointRelationService.removeRelation(authentication.getName(), spaceId, relationId);
    }
}
