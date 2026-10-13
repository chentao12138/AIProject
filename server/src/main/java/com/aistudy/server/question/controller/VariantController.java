package com.aistudy.server.question.controller;

import com.aistudy.server.ai.provider.AiProviderException;
import com.aistudy.server.question.dto.AiVariantDto;
import com.aistudy.server.question.dto.AiVariantDto.SavedVariantResponse;
import com.aistudy.server.question.dto.AiVariantDto.VariantRequest;
import com.aistudy.server.question.dto.AiVariantDto.VariantResponse;
import com.aistudy.server.question.service.AiVariantService;
import com.aistudy.server.practice.entity.PracticeSession;
import com.aistudy.server.practice.entity.PracticeSessionQuestion;
import com.aistudy.server.practice.mapper.PracticeSessionMapper;
import com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper;
import com.aistudy.server.practice.service.PracticeSessionService;
import com.aistudy.server.space.service.LearningSpaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * §5.8 — AI variant question REST API.
 *
 * <pre>
 *   POST /api/v1/practice-sessions/{sessionId}/questions/{questionId}/variant?save=false
 *        → 200 VariantResponse (session must be SUBMITTED)
 *        ?save=true  → 201 SavedVariantResponse (creates AI_DERIVED DRAFT question)
 * </pre>
 *
 * <p>Only SUBMITTED sessions can generate variants. The variant inherits
 * the original question's answer data for deterministic grading; it
 * does NOT modify the original grading truth.
 */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class VariantController {

    private final AiVariantService aiVariantService;
    private final PracticeSessionMapper practiceSessionMapper;
    private final PracticeSessionQuestionMapper practiceSessionQuestionMapper;
    private final LearningSpaceService learningSpaceService;

    public VariantController(AiVariantService aiVariantService,
                             PracticeSessionMapper practiceSessionMapper,
                             PracticeSessionQuestionMapper practiceSessionQuestionMapper,
                             LearningSpaceService learningSpaceService) {
        this.aiVariantService = aiVariantService;
        this.practiceSessionMapper = practiceSessionMapper;
        this.practiceSessionQuestionMapper = practiceSessionQuestionMapper;
        this.learningSpaceService = learningSpaceService;
    }

    @Operation(
            description = "Requires a SUBMITTED practice session, else 409 `code: VARIANT_SESSION_NOT_SUBMITTED`. The generated variant is stored as a DRAFT and never published automatically.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "variant created as DRAFT"),
                    @ApiResponse(responseCode = "409", description = "`code: VARIANT_SESSION_NOT_SUBMITTED`")
            })
    @PostMapping(value = "/practice-sessions/{sessionId}/questions/{questionId}/variant",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object variantFromPractice(@PathVariable Long sessionId,
                                      @PathVariable Long questionId,
                                      @RequestParam(defaultValue = "false") boolean save,
                                      @Valid @RequestBody VariantRequest request,
                                      Authentication authentication) {
        String ownerSubject = authentication.getName();
        PracticeSession session = practiceSessionMapper.selectById(sessionId);
        if (session == null || !ownerSubject.equals(session.getUserSubject())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PracticeSession not found");
        }
        if (learningSpaceService.getMine(ownerSubject, session.getSpaceId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        if (!PracticeSessionService.STATUS_SUBMITTED.equals(session.getStatus())) {
            throw ApiException.conflict(ApiErrorCodes.VARIANT_SESSION_NOT_SUBMITTED,
                    "practice session must be SUBMITTED before generating variants: "
                            + session.getStatus());
        }
        List<PracticeSessionQuestion> slots = practiceSessionQuestionMapper
                .selectBySessionId(session.getSpaceId(), sessionId);
        boolean belongs = slots.stream().anyMatch(s -> s.getQuestionId().equals(questionId));
        if (!belongs) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "questionId is not part of this session");
        }
        try {
            VariantResponse variant = aiVariantService.generateVariant(
                    ownerSubject, session.getSpaceId(), questionId, request);
            if (save) {
                AiVariantDto.SavedVariantResponse saved = aiVariantService.saveVariant(
                        ownerSubject, session.getSpaceId(), questionId, variant,
                        request.knowledgePointIds());
                // Returning the body is not optional: raising 201 as a
                // ResponseStatusException pushed the new questionId and its
                // DRAFT status into the error channel, where the client could
                // not read them as a typed response.
                return ResponseEntity.status(HttpStatus.CREATED).body(saved);
            }
            return ResponseEntity.ok(variant);
        } catch (AiProviderException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI variant generation failed: " + e.getMessage());
        }
    }
}
