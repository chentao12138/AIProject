package com.aistudy.server.question.controller;

import com.aistudy.server.question.dto.CreateQuestionRequest;
import com.aistudy.server.question.dto.QuestionAuthoringResponse;
import com.aistudy.server.question.dto.UpdateQuestionRequest;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.service.QuestionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
        Question created = questionService.create(authentication.getName(), spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace or KnowledgePoint not found");
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
        Question question = questionService.getMine(authentication.getName(), spaceId, questionId);
        if (question == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found");
        }
        return toAuthoring(question);
    }

    @PutMapping(value = "/{questionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionAuthoringResponse update(@PathVariable Long spaceId,
                                            @PathVariable Long questionId,
                                            @Valid @RequestBody UpdateQuestionRequest request,
                                            Authentication authentication) {
        Question updated = questionService.update(authentication.getName(), spaceId, questionId, request);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found");
        }
        return toAuthoring(updated);
    }

    @PostMapping(value = "/{questionId}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionAuthoringResponse archive(@PathVariable Long spaceId,
                                             @PathVariable Long questionId,
                                             Authentication authentication) {
        Question updated = questionService.archive(authentication.getName(), spaceId, questionId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found");
        }
        return toAuthoring(updated);
    }

    @PostMapping(value = "/{questionId}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionAuthoringResponse restore(@PathVariable Long spaceId,
                                             @PathVariable Long questionId,
                                             Authentication authentication) {
        Question updated = questionService.restore(authentication.getName(), spaceId, questionId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found");
        }
        return toAuthoring(updated);
    }

    @PostMapping(value = "/{questionId}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionAuthoringResponse publish(@PathVariable Long spaceId,
                                             @PathVariable Long questionId,
                                             Authentication authentication) {
        Question question = questionService.publish(authentication.getName(), spaceId, questionId);
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
