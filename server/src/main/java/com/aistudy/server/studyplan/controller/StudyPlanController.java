package com.aistudy.server.studyplan.controller;

import com.aistudy.server.studyplan.dto.StudyPlanDto.GenerateStudyPlanRequest;
import com.aistudy.server.studyplan.dto.StudyPlanDto.StudyPlanView;
import com.aistudy.server.studyplan.dto.StudyPlanDto.StudyTaskView;
import com.aistudy.server.studyplan.service.StudyPlanService;
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

/**
 * BUSINESS-016 — StudyPlan REST API (singular "current plan" V1
 * semantics per api-guidelines.md §2).
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/study-plan/generate   → 201 plan
 *   GET  /api/v1/spaces/{spaceId}/study-plan            → 200 current plan
 *   POST /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/complete → 200
 * </pre>
 *
 * <p>Generation is deterministic and consumes server-side facts
 * (review tasks, mastery, diagnosis) — no client-submitted scores.
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
}
