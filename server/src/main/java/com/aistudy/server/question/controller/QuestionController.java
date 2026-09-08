package com.aistudy.server.question.controller;

import com.aistudy.server.question.dto.CreateQuestionRequest;
import com.aistudy.server.question.dto.QuestionAuthoringResponse;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.service.QuestionService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * BUSINESS-008 — production Question REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/questions        → 201 authoring view
 *   GET  /api/v1/spaces/{spaceId}/questions        → 200 typed list
 *        ?status=&amp;questionType=&amp;knowledgePointId= (all optional)
 *   GET  /api/v1/spaces/{spaceId}/questions/{questionId} → 200 / 404
 *   POST /api/v1/spaces/{spaceId}/questions/{questionId}/publish → 200
 * </pre>
 *
 * <p>All endpoints are owner-scoped; 404 uniformly expresses
 * absent/not-owned/cross-space (anti-probing). Responses are the
 * AUTHORING view (answer included) — practice/exam views never call
 * these endpoints. Bearer required (class-level
 * {@code @SecurityRequirement}).
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/questions")
@SecurityRequirement(name = "bearerAuth")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionAuthoringResponse create(@PathVariable Long spaceId,
                                            @Valid @RequestBody CreateQuestionRequest request,
                                            Authentication authentication) {
        Question created = questionService.create(
                authentication.getName(), spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "LearningSpace or KnowledgePoint not found");
        }
        return toAuthoring(created);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<QuestionAuthoringResponse> list(@PathVariable Long spaceId,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String questionType,
                                                @RequestParam(required = false) Long knowledgePointId,
                                                Authentication authentication) {
        List<Question> questions = questionService.listMine(
                authentication.getName(), spaceId, status, questionType, knowledgePointId);
        if (questions == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return questions.stream().map(this::toAuthoring).toList();
    }

    @GetMapping(value = "/{questionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionAuthoringResponse get(@PathVariable Long spaceId,
                                         @PathVariable Long questionId,
                                         Authentication authentication) {
        Question question = questionService.getMine(
                authentication.getName(), spaceId, questionId);
        if (question == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found");
        }
        return toAuthoring(question);
    }

    @PostMapping(value = "/{questionId}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionAuthoringResponse publish(@PathVariable Long spaceId,
                                             @PathVariable Long questionId,
                                             Authentication authentication) {
        Question question = questionService.publish(
                authentication.getName(), spaceId, questionId);
        if (question == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found");
        }
        return toAuthoring(question);
    }

    private QuestionAuthoringResponse toAuthoring(Question question) {
        return QuestionAuthoringResponse.from(
                question,
                questionService.optionsOf(question),
                questionService.knowledgePointsOf(question));
    }
}
