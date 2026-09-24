package com.aistudy.server.studyplan.controller;

import com.aistudy.server.studyplan.dto.StudyPlanDto.GenerateStudyPlanRequest;
import com.aistudy.server.studyplan.dto.StudyPlanDto.StudyPlanView;
import com.aistudy.server.studyplan.dto.StudyPlanDto.StudyTaskView;
import com.aistudy.server.studyplan.service.StudyPlanService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-016 — StudyPlan REST API (singular "current plan" V1
 * semantics per api-guidelines.md §2).
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/study-plan/generate   → 201 plan
 *   GET  /api/v1/spaces/{spaceId}/study-plan            → 200 current plan
 *   POST /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/complete → 200
 *   POST /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/skip → 200
 *   POST /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/unskip → 200
 *   POST /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/in-progress → 200
 *   PATCH /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/due-at → 200
 *   PATCH /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/priority → 200
 *   POST /api/v1/spaces/{spaceId}/study-plan/{planId}/reorder → 200
 *   PATCH /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/title-reason → 200
 *   POST /api/v1/spaces/{spaceId}/study-plan/{planId}/regenerate → 200
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
@SecurityRequirement(name = "bearerAuth")
public class StudyPlanController {

    private final StudyPlanService studyPlanService;

    public StudyPlanController(StudyPlanService studyPlanService) {
        this.studyPlanService = studyPlanService;
    }

    @PostMapping(value = "/study-plan/generate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StudyPlanView generate(@PathVariable Long spaceId,
                                  @Valid @RequestBody GenerateStudyPlanRequest request,
                                  Authentication authentication) {
        return studyPlanService.generate(authentication.getName(), spaceId, request);
    }

    @GetMapping(value = "/study-plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyPlanView get(@PathVariable Long spaceId,
                             Authentication authentication) {
        StudyPlanView view = studyPlanService.getCurrent(authentication.getName(), spaceId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "StudyPlan not found");
        }
        return view;
    }

    @PostMapping(value = "/study-plan/tasks/{taskId}/complete",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyTaskView complete(@PathVariable Long spaceId,
                                  @PathVariable Long taskId,
                                  Authentication authentication) {
        return studyPlanService.complete(authentication.getName(), spaceId, taskId);
    }

    @PostMapping(value = "/study-plan/tasks/{taskId}/skip",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyTaskView skip(@PathVariable Long spaceId,
                              @PathVariable Long taskId,
                              Authentication authentication) {
        return studyPlanService.skip(authentication.getName(), spaceId, taskId);
    }

    @PostMapping(value = "/study-plan/tasks/{taskId}/unskip",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyTaskView unskip(@PathVariable Long spaceId,
                                @PathVariable Long taskId,
                                Authentication authentication) {
        return studyPlanService.unskip(authentication.getName(), spaceId, taskId);
    }

    @PostMapping(value = "/study-plan/tasks/{taskId}/in-progress",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyTaskView markInProgress(@PathVariable Long spaceId,
                                        @PathVariable Long taskId,
                                        Authentication authentication) {
        return studyPlanService.markInProgress(authentication.getName(), spaceId, taskId);
    }

    @PatchMapping(value = "/study-plan/tasks/{taskId}/due-at",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyTaskView adjustDueAt(@PathVariable Long spaceId,
                                     @PathVariable Long taskId,
                                     @RequestBody @Valid AdjustDueAtRequest request,
                                     Authentication authentication) {
        return studyPlanService.adjustDueAt(
                authentication.getName(), spaceId, taskId, request.dueAt());
    }

    public record AdjustDueAtRequest(
            @Valid java.time.LocalDateTime dueAt) {
    }

    @PatchMapping(value = "/study-plan/tasks/{taskId}/priority",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyTaskView adjustPriority(@PathVariable Long spaceId,
                                        @PathVariable Long taskId,
                                        @RequestBody @Valid AdjustPriorityRequest request,
                                        Authentication authentication) {
        return studyPlanService.adjustPriority(
                authentication.getName(), spaceId, taskId, request.priority());
    }

    public record AdjustPriorityRequest(
            @NotBlank @Size(max = 16) String priority) {
    }

    @PostMapping(value = "/study-plan/{planId}/reorder",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyPlanView reorder(@PathVariable Long spaceId,
                                 @PathVariable Long planId,
                                 @RequestBody @Valid ReorderRequest request,
                                 Authentication authentication) {
        return studyPlanService.reorder(
                authentication.getName(), spaceId, planId, request.taskIds());
    }

    public record ReorderRequest(
            @NotEmpty List<Long> taskIds) {
    }

    @PatchMapping(value = "/study-plan/tasks/{taskId}/title-reason",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyTaskView editTitleReason(@PathVariable Long spaceId,
                                         @PathVariable Long taskId,
                                         @RequestBody @Valid EditTitleReasonRequest request,
                                         Authentication authentication) {
        return studyPlanService.editTitleReason(
                authentication.getName(), spaceId, taskId,
                request.title(), request.reason());
    }

    public record EditTitleReasonRequest(
            @Size(max = 255) String title,
            String reason) {
    }

    @PostMapping(value = "/study-plan/{planId}/regenerate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StudyPlanView regenerate(@PathVariable Long spaceId,
                                    @PathVariable Long planId,
                                    Authentication authentication) {
        return studyPlanService.regenerate(authentication.getName(), spaceId, planId);
    }
}
