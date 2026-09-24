package com.aistudy.server.knowledge.category.controller;

import com.aistudy.server.knowledge.category.dto.CreateKnowledgeCategoryRequest;
import com.aistudy.server.knowledge.category.dto.KnowledgeCategoryResponse;
import com.aistudy.server.knowledge.category.dto.UpdateKnowledgeCategoryRequest;
import com.aistudy.server.knowledge.category.entity.KnowledgeCategory;
import com.aistudy.server.knowledge.category.service.KnowledgeCategoryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/knowledge-categories")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgeCategoryController {

    private final KnowledgeCategoryService knowledgeCategoryService;

    public KnowledgeCategoryController(KnowledgeCategoryService knowledgeCategoryService) {
        this.knowledgeCategoryService = knowledgeCategoryService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeCategoryResponse create(@PathVariable Long spaceId,
                                            @Valid @RequestBody CreateKnowledgeCategoryRequest request,
                                            Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgeCategory created = knowledgeCategoryService.create(ownerSubject, spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace or parent category not found");
        }
        return KnowledgeCategoryResponse.from(created);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KnowledgeCategoryResponse> list(@PathVariable Long spaceId,
                                                Authentication authentication) {
        String ownerSubject = authentication.getName();
        List<KnowledgeCategory> categories = knowledgeCategoryService.listMine(ownerSubject, spaceId);
        if (categories == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return categories.stream().map(KnowledgeCategoryResponse::from).toList();
    }

    @GetMapping(value = "/{categoryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeCategoryResponse get(@PathVariable Long spaceId,
                                         @PathVariable Long categoryId,
                                         Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgeCategory category = knowledgeCategoryService.getMine(ownerSubject, spaceId, categoryId);
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgeCategory not found");
        }
        return KnowledgeCategoryResponse.from(category);
    }

    @PutMapping(value = "/{categoryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeCategoryResponse update(@PathVariable Long spaceId,
                                            @PathVariable Long categoryId,
                                            @Valid @RequestBody UpdateKnowledgeCategoryRequest request,
                                            Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgeCategory updated = knowledgeCategoryService.update(ownerSubject, spaceId, categoryId, request);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgeCategory not found");
        }
        return KnowledgeCategoryResponse.from(updated);
    }

    @PostMapping(value = "/{categoryId}/reparent", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeCategoryResponse reparent(@PathVariable Long spaceId,
                                              @PathVariable Long categoryId,
                                              @Valid @RequestBody ReparentCategoryRequest request,
                                              Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgeCategory updated = knowledgeCategoryService.reparent(ownerSubject, spaceId, categoryId, request.newParentId());
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgeCategory not found or invalid parent");
        }
        return KnowledgeCategoryResponse.from(updated);
    }

    @PostMapping(value = "/{categoryId}/reorder", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeCategoryResponse reorder(@PathVariable Long spaceId,
                                             @PathVariable Long categoryId,
                                             @Valid @RequestBody ReorderCategoryRequest request,
                                             Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgeCategory updated = knowledgeCategoryService.reorder(ownerSubject, spaceId, categoryId, request.sortOrder());
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "KnowledgeCategory not found");
        }
        return KnowledgeCategoryResponse.from(updated);
    }

    @DeleteMapping(value = "/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long spaceId,
                       @PathVariable Long categoryId,
                       Authentication authentication) {
        String ownerSubject = authentication.getName();
        knowledgeCategoryService.delete(ownerSubject, spaceId, categoryId);
    }
}
