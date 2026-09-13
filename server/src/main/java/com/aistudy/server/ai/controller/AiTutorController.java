package com.aistudy.server.ai.controller;

import com.aistudy.server.ai.coach.AiStudyCoachService;
import com.aistudy.server.ai.dto.AiDto.CreateConversationRequest;
import com.aistudy.server.ai.dto.AiDto.ConversationPageResponse;
import com.aistudy.server.ai.dto.AiDto.ConversationView;
import com.aistudy.server.ai.dto.AiDto.ExplanationResponse;
import com.aistudy.server.ai.dto.AiDto.MessagePageResponse;
import com.aistudy.server.ai.dto.AiDto.SendMessageRequest;
import com.aistudy.server.ai.dto.AiDto.SendMessageResponse;
import com.aistudy.server.ai.dto.AiDto.StudyCoachRequest;
import com.aistudy.server.ai.dto.AiDto.StudyCoachResponse;
import com.aistudy.server.ai.explain.AiAnswerExplanationService;
import com.aistudy.server.ai.service.AiConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI-004 — AI tutor conversation REST API (synchronous).
 */
@RestController
@RequestMapping(value = "/api/v1/spaces/{spaceId}/ai", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
public class AiTutorController {

    private final AiConversationService aiConversationService;
    private final AiAnswerExplanationService answerExplanationService;
    private final AiStudyCoachService studyCoachService;

    public AiTutorController(AiConversationService aiConversationService,
                             AiAnswerExplanationService answerExplanationService,
                             AiStudyCoachService studyCoachService) {
        this.aiConversationService = aiConversationService;
        this.answerExplanationService = answerExplanationService;
        this.studyCoachService = studyCoachService;
    }

    @Operation(summary = "Create an AI tutor conversation in this LearningSpace")
    @PostMapping(value = "/conversations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView createConversation(
            @PathVariable Long spaceId,
            @Valid @RequestBody(required = false) CreateConversationRequest request,
            Authentication authentication) {
        String title = request == null ? null : request.title();
        return aiConversationService.createConversation(authentication.getName(), spaceId, title);
    }

    @Operation(summary = "List my ACTIVE AI conversations in this LearningSpace")
    @GetMapping("/conversations")
    public ConversationPageResponse listConversations(
            @PathVariable Long spaceId,
            @RequestParam(value = "page", required = false, defaultValue = "0") @Min(0) Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20")
            @Min(1) @Max(100) Integer size,
            Authentication authentication) {
        return aiConversationService.listConversations(
                authentication.getName(), spaceId, page, size);
    }

    @Operation(summary = "Get one of my AI conversations")
    @GetMapping("/conversations/{conversationId}")
    public ConversationView getConversation(
            @PathVariable Long spaceId,
            @PathVariable Long conversationId,
            Authentication authentication) {
        return aiConversationService.getConversation(
                authentication.getName(), spaceId, conversationId);
    }

    @Operation(summary = "Archive one of my AI conversations")
    @PostMapping("/conversations/{conversationId}/archive")
    public ConversationView archiveConversation(
            @PathVariable Long spaceId,
            @PathVariable Long conversationId,
            Authentication authentication) {
        return aiConversationService.archiveConversation(
                authentication.getName(), spaceId, conversationId);
    }

    @Operation(summary = "List messages of one of my AI conversations")
    @GetMapping("/conversations/{conversationId}/messages")
    public MessagePageResponse listMessages(
            @PathVariable Long spaceId,
            @PathVariable Long conversationId,
            @RequestParam(value = "page", required = false, defaultValue = "0") @Min(0) Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "50")
            @Min(1) @Max(100) Integer size,
            Authentication authentication) {
        return aiConversationService.listMessages(
                authentication.getName(), spaceId, conversationId, page, size);
    }

    @Operation(summary = "Send a user message and receive the AI tutor reply")
    @PostMapping(value = "/conversations/{conversationId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SendMessageResponse sendMessage(
            @PathVariable Long spaceId,
            @PathVariable Long conversationId,
            @Valid @RequestBody SendMessageRequest request,
            Authentication authentication) {
        return aiConversationService.sendMessage(
                authentication.getName(), spaceId, conversationId, request.content());
    }

    @Operation(summary = "Explain a submitted practice answer (post-submit only)")
    @PostMapping("/explanations/practice-answers/{answerId}")
    public ExplanationResponse explainPracticeAnswer(
            @PathVariable Long spaceId,
            @PathVariable Long answerId,
            Authentication authentication) {
        return answerExplanationService.explainPracticeAnswer(
                authentication.getName(), spaceId, answerId);
    }

    @Operation(summary = "Explain a submitted exam answer (post-submit only)")
    @PostMapping("/explanations/exam-answers/{answerId}")
    public ExplanationResponse explainExamAnswer(
            @PathVariable Long spaceId,
            @PathVariable Long answerId,
            Authentication authentication) {
        return answerExplanationService.explainExamAnswer(
                authentication.getName(), spaceId, answerId);
    }

    @Operation(summary = "Read-only study coach: recommend next study steps")
    @PostMapping(value = "/study-coach", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StudyCoachResponse studyCoach(
            @PathVariable Long spaceId,
            @Valid @RequestBody(required = false) StudyCoachRequest request,
            Authentication authentication) {
        String question = request == null ? null : request.question();
        return studyCoachService.coach(authentication.getName(), spaceId, question);
    }
}
