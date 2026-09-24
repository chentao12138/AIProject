package com.aistudy.server.wrong.controller;

import com.aistudy.server.wrong.dto.WrongReviewResponse.WrongQuestionView;
import com.aistudy.server.wrong.service.WrongQuestionReviewService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * BUSINESS-011 — WrongQuestion REST API.
 *
 * <pre>
 *   GET /api/v1/spaces/{spaceId}/wrong-questions → 200 typed list
 *        (owner+user scoped, newest wrong first)
 * </pre>
 *
 * <p>No delete/dismiss API in V1 (status transitions come from real
 * review completions only).
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/wrong-questions")
@SecurityRequirement(name = "bearerAuth")
public class WrongQuestionController {

    private final WrongQuestionReviewService wrongQuestionReviewService;

    public WrongQuestionController(WrongQuestionReviewService wrongQuestionReviewService) {
        this.wrongQuestionReviewService = wrongQuestionReviewService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<WrongQuestionView> list(@PathVariable Long spaceId,
                                        Authentication authentication) {
        var questions = wrongQuestionReviewService.listWrongQuestions(
                authentication.getName(), spaceId);
        if (questions == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return questions.stream().map(WrongQuestionView::from).toList();
    }

    @PostMapping(value = "/{wrongQuestionId}/dismiss", produces = MediaType.APPLICATION_JSON_VALUE)
    public WrongQuestionView dismiss(@PathVariable Long spaceId,
                                     @PathVariable Long wrongQuestionId,
                                     Authentication authentication) {
        var wq = wrongQuestionReviewService.dismissWrongQuestion(
                authentication.getName(), spaceId, wrongQuestionId);
        if (wq == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WrongQuestion not found");
        }
        return WrongQuestionView.from(wq);
    }

    @PostMapping(value = "/{wrongQuestionId}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    public WrongQuestionView restore(@PathVariable Long spaceId,
                                     @PathVariable Long wrongQuestionId,
                                     Authentication authentication) {
        var wq = wrongQuestionReviewService.restoreWrongQuestion(
                authentication.getName(), spaceId, wrongQuestionId);
        if (wq == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WrongQuestion not found");
        }
        return WrongQuestionView.from(wq);
    }
}
