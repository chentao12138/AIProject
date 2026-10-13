package com.aistudy.server.practice.controller;

import com.aistudy.server.practice.dto.PracticeAnswerRequest;
import com.aistudy.server.practice.dto.PracticeAnswerResponse.PracticeAnswerView;
import com.aistudy.server.practice.dto.PracticeAnswerResponse.PracticeSubmitView;
import com.aistudy.server.practice.dto.PracticeSessionResponse.PracticeResultView;
import com.aistudy.server.practice.service.PracticeAnswerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

    @Operation(
            description = "Grades one answer immediately and stores it. 409 codes: PRACTICE_SESSION_NOT_IN_PROGRESS (the session is not open), PRACTICE_ANSWER_STALE.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "answer with feedback"),
                    @ApiResponse(responseCode = "409", description = "conflict with a stable `code`")
            })
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

    @Operation(
            description = "Closes the session and stores the summary in the same transaction. 409 `code: PRACTICE_SESSION_NOT_IN_PROGRESS` when it is not open, `code: PRACTICE_SESSION_STALE` when another request moved it on first.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "submit summary"),
                    @ApiResponse(responseCode = "409", description = "conflict with a stable `code`")
            })
    @PostMapping(value = "/finish", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public PracticeSubmitView finish(@PathVariable Long spaceId,
                                     @PathVariable Long sessionId,
                                     Authentication authentication) {
        return practiceAnswerService.finish(
                authentication.getName(), spaceId, sessionId);
    }

    /**
     * C06: the result of a finished session, readable again after the
     * submission response is gone. 409 with
     * {@code PRACTICE_SESSION_NOT_SUBMITTED} while the session is open.
     */
    @Operation(
            description = "Score summary written at submit time. 409 `code: PRACTICE_SESSION_NOT_SUBMITTED` while the session is still open — zeros would be a fabricated score — and `code: PRACTICE_RESULT_NOT_RECORDED` for a session submitted before results were stored.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "stored result"),
                    @ApiResponse(responseCode = "409", description = "conflict with a stable `code`")
            })
    @GetMapping(value = "/result", produces = MediaType.APPLICATION_JSON_VALUE)
    public PracticeResultView result(@PathVariable Long spaceId,
                                     @PathVariable Long sessionId,
                                     Authentication authentication) {
        return practiceAnswerService.result(
                authentication.getName(), spaceId, sessionId);
    }

    @GetMapping(value = "/answers", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PracticeAnswerView> listAnswers(@PathVariable Long spaceId,
                                                @PathVariable Long sessionId,
                                                Authentication authentication) {
        List<PracticeAnswerView> views = practiceAnswerService.reviewAnswers(
                authentication.getName(), spaceId, sessionId);
        if (views == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PracticeSession not found");
        }
        return views;
    }
}
