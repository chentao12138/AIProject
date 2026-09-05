package com.aistudy.server.knowledge.category.controller;

import com.aistudy.server.knowledge.category.dto.CreateKnowledgeCategoryRequest;
import com.aistudy.server.knowledge.category.dto.KnowledgeCategoryResponse;
import com.aistudy.server.knowledge.category.entity.KnowledgeCategory;
import com.aistudy.server.knowledge.category.service.KnowledgeCategoryService;
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
 * BUSINESS-003 — production KnowledgeCategory REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/knowledge-categories            → 201
 *   GET  /api/v1/spaces/{spaceId}/knowledge-categories            → 200 typed List
 *   GET  /api/v1/spaces/{spaceId}/knowledge-categories/{categoryId} → 200 / 404
 * </pre>
 *
 * <p>All endpoints require Bearer authentication (class-level
 * {@code @SecurityRequirement(name = "bearerAuth")}); runtime
 * enforcement comes from the existing SecurityFilterChain
 * ({@code anyRequest().authenticated()} + JWT Resource Server), which
 * already covers {@code /api/v1/**} without any change here.
 * CSRF: the existing path-scoped ignore {@code /api/v1/spaces/**}
 * already covers these nested routes — no SecurityConfig change.
 *
 * <p>404 uniformly expresses absent/not-owned/parent-invalid
 * (anti-probing), consistent with BUSINESS-001/002.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/knowledge-categories")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgeCategoryController {

    private final KnowledgeCategoryService knowledgeCategoryService;

    public KnowledgeCategoryController(KnowledgeCategoryService knowledgeCategoryService) {
        this.knowledgeCategoryService = knowledgeCategoryService;
    }

    /**
     * Creates a category (root or child) inside the caller's own
     * space. 404 when the space is not owned or the parentId is
     * invalid (other space / other owner / absent); 400 on
     * validation failure.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeCategoryResponse create(@PathVariable Long spaceId,
                                            @Valid @RequestBody CreateKnowledgeCategoryRequest request,
                                            Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgeCategory created = knowledgeCategoryService.create(ownerSubject, spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "LearningSpace or parent category not found");
        }
        return KnowledgeCategoryResponse.from(created);
    }

    /**
     * Lists the caller's own space's categories (sort_order ASC).
     * 404 when the space is not owned.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KnowledgeCategoryResponse> list(@PathVariable Long spaceId,
                                                Authentication authentication) {
        String ownerSubject = authentication.getName();
        List<KnowledgeCategory> categories = knowledgeCategoryService.listMine(ownerSubject, spaceId);
        if (categories == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "LearningSpace not found");
        }
        return categories.stream()
                .map(KnowledgeCategoryResponse::from)
                .toList();
    }

    /**
     * Returns ONE category of the caller's own space. 404 when
     * absent / other space / other owner (owner-scoped SQL).
     */
    @GetMapping(value = "/{categoryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeCategoryResponse get(@PathVariable Long spaceId,
                                         @PathVariable Long categoryId,
                                         Authentication authentication) {
        String ownerSubject = authentication.getName();
        KnowledgeCategory category = knowledgeCategoryService.getMine(ownerSubject, spaceId, categoryId);
        if (category == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "KnowledgeCategory not found");
        }
        return KnowledgeCategoryResponse.from(category);
    }
}
