package com.aistudy.server.space.controller;

import com.aistudy.server.space.dto.CreateLearningSpaceRequest;
import com.aistudy.server.space.dto.LearningSpaceResponse;
import com.aistudy.server.space.dto.RenameLearningSpaceRequest;
import com.aistudy.server.space.entity.LearningSpace;
import com.aistudy.server.space.service.LearningSpaceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces")
@SecurityRequirement(name = "bearerAuth")
public class LearningSpaceController {

    private final LearningSpaceService learningSpaceService;

    public LearningSpaceController(LearningSpaceService learningSpaceService) {
        this.learningSpaceService = learningSpaceService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LearningSpaceResponse create(@Valid @RequestBody CreateLearningSpaceRequest request,
                                        Authentication authentication) {
        String ownerSubject = authentication.getName();
        LearningSpace created = learningSpaceService.create(ownerSubject, request);
        return LearningSpaceResponse.from(created);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<LearningSpaceResponse> list(Authentication authentication) {
        String ownerSubject = authentication.getName();
        return learningSpaceService.listMine(ownerSubject).stream()
                .map(LearningSpaceResponse::from)
                .toList();
    }

    @GetMapping(value = "/{spaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LearningSpaceResponse get(@PathVariable Long spaceId,
                                     Authentication authentication) {
        String ownerSubject = authentication.getName();
        LearningSpace space = learningSpaceService.getMine(ownerSubject, spaceId);
        if (space == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return LearningSpaceResponse.from(space);
    }

    @PutMapping(value = "/{spaceId}/rename", produces = MediaType.APPLICATION_JSON_VALUE)
    public LearningSpaceResponse rename(@PathVariable Long spaceId,
                                        @Valid @RequestBody RenameLearningSpaceRequest request,
                                        Authentication authentication) {
        String ownerSubject = authentication.getName();
        LearningSpace updated = learningSpaceService.rename(ownerSubject, spaceId, request.name(), request.description());
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return LearningSpaceResponse.from(updated);
    }

    @PostMapping(value = "/{spaceId}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public LearningSpaceResponse archive(@PathVariable Long spaceId,
                                         Authentication authentication) {
        String ownerSubject = authentication.getName();
        LearningSpace updated = learningSpaceService.archive(ownerSubject, spaceId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return LearningSpaceResponse.from(updated);
    }

    @PostMapping(value = "/{spaceId}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    public LearningSpaceResponse restore(@PathVariable Long spaceId,
                                         Authentication authentication) {
        String ownerSubject = authentication.getName();
        LearningSpace updated = learningSpaceService.restore(ownerSubject, spaceId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return LearningSpaceResponse.from(updated);
    }
}
