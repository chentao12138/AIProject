package com.aistudy.server.practice.controller;

import com.aistudy.server.practice.dto.PracticeAnswerRequest;
import com.aistudy.server.practice.dto.PracticeAnswerResponse.PracticeAnswerView;
import com.aistudy.server.practice.dto.PracticeAnswerResponse.PracticeSubmitView;
import com.aistudy.server.practice.entity.PracticeAnswer;
import com.aistudy.server.practice.service.PracticeAnswerService;
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

import java.util.List;

/**
 * BUSINESS-010 — Practice answer + finish REST API.
 *
 * <pre>
 *   POST .../practice-sessions/{sessionId}/answers → 200 answer+feedback
 *        (IN_PROGRESS only; upsert per slot; 409 when SUBMITTED)
 *   POST .../practice-sessions/{sessionId}/finish  → 200 submit summary
 *        (IN_PROGRESS → SUBMITTED; objective grading; 409 otherwise)
 * </pre>
 *
 * <p>Endpoint name follows api-guidelines.md §8 ({@code /finish}).
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/practice-sessions/{sessionId}")
@SecurityRequirement(name = "bearerAuth")
public class PracticeAnswerController {

    private final PracticeAnswerService practiceAnswerService;

    public PracticeAnswerController(PracticeAnswerService practiceAnswerService) {
        this.practiceAnswerService = practiceAnswerService;
    }

    @PostMapping(value = "/answers", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public PracticeAnswerView answer(@PathVariable Long spaceId,
                                     @PathVariable Long sessionId,
                                     @Valid @RequestBody PracticeAnswerRequest request,
                                     Authentication authentication) {
        return practiceAnswerService.answer(
                authentication.getName(), spaceId, sessionId, request);
    }

    @PostMapping(value = "/finish", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public PracticeSubmitView finish(@PathVariable Long spaceId,
                                     @PathVariable Long sessionId,
                                     Authentication authentication) {
        return practiceAnswerService.finish(
                authentication.getName(), spaceId, sessionId);
    }

    @GetMapping(value = "/answers", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PracticeAnswerView> listAnswers(@PathVariable Long spaceId,
                                                @PathVariable Long sessionId,
                                                Authentication authentication) {
        List<PracticeAnswer> answers = practiceAnswerService.listAnswers(
                authentication.getName(), spaceId, sessionId);
        if (answers == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PracticeSession not found");
        }
        return answers.stream()
                .map(a -> PracticeAnswerView.from(
                        a, a.getIsCorrect() != null ? a.getCorrectAnswerSummary() : null, null))
                .toList();
    }
}
