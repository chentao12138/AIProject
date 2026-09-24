package com.aistudy.server.ai.controller;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.service.AIGenerationJobService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Space-scoped AI generation jobs. Requester always from JWT.
 * Allowed jobType: content-structure | knowledge-extraction | question-generation
 * | exam-diagnosis-narrative.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/ai/generation-jobs")
@SecurityRequirement(name = "bearerAuth")
public class AiGenerationJobController {

    public record CreateGenerationJobRequest(
            @NotBlank @Size(max = 32) String jobType,
            Long sourceId,
            Long revisionId,
            Long knowledgePointId,
            @Size(max = 8000) String prompt
    ) {
    }

    private final AIGenerationJobService service;
    private final com.aistudy.server.space.service.LearningSpaceService learningSpaceService;

    public AiGenerationJobController(AIGenerationJobService service,
                                     com.aistudy.server.space.service.LearningSpaceService learningSpaceService) {
        this.service = service;
        this.learningSpaceService = learningSpaceService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AIGenerationJob create(@PathVariable Long spaceId,
                                  @Valid @RequestBody CreateGenerationJobRequest request,
                                  Authentication authentication) {
        if (learningSpaceService.getMine(authentication.getName(), spaceId) == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        String type = request.jobType();
        if (!java.util.Set.of("content-structure", "knowledge-extraction",
                "question-generation", "exam-diagnosis-narrative").contains(type)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "unsupported jobType");
        }
        return service.create(type, authentication.getName(), spaceId,
                request.sourceId(), request.revisionId(), request.knowledgePointId());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AIGenerationJob> list(@PathVariable Long spaceId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      Authentication authentication) {
        return service.listMine(authentication.getName(), spaceId, page, Math.min(100, Math.max(1, size)));
    }

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AIGenerationJob get(@PathVariable Long spaceId,
                               @PathVariable Long jobId,
                               Authentication authentication) {
        AIGenerationJob job = service.getMine(jobId, authentication.getName());
        if (job == null || !spaceId.equals(job.getSpaceId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "AIGenerationJob not found");
        }
        return job;
    }

    @PostMapping("/{jobId}/retry")
    public AIGenerationJob retry(@PathVariable Long spaceId,
                                 @PathVariable Long jobId,
                                 Authentication authentication) {
        AIGenerationJob job = service.getMine(jobId, authentication.getName());
        if (job == null || !spaceId.equals(job.getSpaceId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "AIGenerationJob not found");
        }
        return service.retry(jobId, authentication.getName());
    }
}
