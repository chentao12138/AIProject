package com.aistudy.server.ai.controller;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.service.AIGenerationJobService;
import com.aistudy.server.ai.service.AIGenerationJobWorker;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
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
 *
 * <p>Accepted jobType is read from
 * {@link AIGenerationJobWorker#SUPPORTED_JOB_TYPES} rather than repeated here,
 * because the two lists drifting is what let {@code exam-diagnosis-narrative}
 * be accepted with no handler behind it (F-22). Anything else is refused with
 * {@code VALIDATION_ERROR} before a row exists.
 *
 * <h2>Scope (F-21)</h2>
 * {@code {spaceId}} is part of every read: list, count, detail and retry are all
 * bound to the path space plus the JWT subject. A job belonging to another
 * space answers 404 with the same text as a job that does not exist — not 403,
 * which would confirm the row exists somewhere else.
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

    /**
     * One page of the caller's jobs for one space. The envelope shape matches
     * the other paged owner reads in this API, and {@code totalElements} is
     * counted under the same space + requester predicate as {@code content}.
     */
    public record GenerationJobPageResponse(
            List<AIGenerationJob> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        static GenerationJobPageResponse from(AIGenerationJobService.JobPage page) {
            return new GenerationJobPageResponse(page.content(), page.page(), page.size(),
                    page.totalElements(), page.totalPages());
        }
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
        requireSpace(authentication.getName(), spaceId);
        String type = request.jobType();
        if (!AIGenerationJobWorker.SUPPORTED_JOB_TYPES.contains(type)) {
            // Named before the row is written: an accepted-but-unhandled type
            // can only ever end up as a failed job that cost a provider call.
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                    "unsupported jobType: " + type + "; accepted jobTypes are "
                            + AIGenerationJobWorker.SUPPORTED_JOB_TYPES.stream().sorted().toList());
        }
        return service.create(type, authentication.getName(), spaceId,
                request.sourceId(), request.revisionId(), request.knowledgePointId());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public GenerationJobPageResponse list(@PathVariable Long spaceId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          Authentication authentication) {
        requireSpace(authentication.getName(), spaceId);
        return GenerationJobPageResponse.from(service.listMine(
                authentication.getName(), spaceId, page, Math.min(100, Math.max(1, size))));
    }

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AIGenerationJob get(@PathVariable Long spaceId,
                               @PathVariable Long jobId,
                               Authentication authentication) {
        requireSpace(authentication.getName(), spaceId);
        AIGenerationJob job = service.getMine(authentication.getName(), spaceId, jobId);
        if (job == null) {
            throw notFound();
        }
        return job;
    }

    @PostMapping("/{jobId}/retry")
    public AIGenerationJob retry(@PathVariable Long spaceId,
                                 @PathVariable Long jobId,
                                 Authentication authentication) {
        requireSpace(authentication.getName(), spaceId);
        // Scope is enforced inside the service so the read, the state guard and
        // the reset cannot be split across two checks with a window between them.
        return service.retry(authentication.getName(), spaceId, jobId);
    }

    private void requireSpace(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.SPACE_NOT_FOUND,
                    "LearningSpace not found");
        }
    }

    /** Identical text for "another space" and "does not exist" (anti-probing). */
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND,
                "AIGenerationJob not found");
    }
}
