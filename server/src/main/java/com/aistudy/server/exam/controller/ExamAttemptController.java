package com.aistudy.server.exam.controller;

import com.aistudy.server.exam.dto.ExamAttemptDto.AttemptPageResponse;
import com.aistudy.server.exam.dto.ExamAttemptDto.AttemptSummaryView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerGradingView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerRequest;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAttemptView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamResultView;
import com.aistudy.server.exam.dto.ExamAttemptDto.GradeAnswerRequest;
import com.aistudy.server.exam.entity.ExamAttempt;
import com.aistudy.server.exam.service.ExamAttemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;

import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.PathVariable;

import org.springframework.web.bind.annotation.PostMapping;

import org.springframework.web.bind.annotation.PutMapping;

import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

/**
 * BUSINESS-013 — Exam attempt REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/exams/{examId}/sessions
 *        → 201 attempt (NOT_STARTED; PUBLISHED exam only)
 *   GET  /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}
 *        → 200 SAFE attempt view (paper questions, no answers)
 *   POST /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/start
 *        → 200 (NOT_STARTED → IN_PROGRESS + deadline)
 *   POST /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/answers
 *        → 200 leak-free stored answer (no correctness fields)
 *   POST /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/submit
 *        → 200 result (correctness revealed; repeated submit 409)
 *   GET  /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/result
 *        → 200 result (SUBMITTED only)
 * </pre>
 *
 * <p>Paths follow api-guidelines.md §9 ({@code exam-attempts}).
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
@SecurityRequirement(name = "bearerAuth")
public class ExamAttemptController {

    private final ExamAttemptService examAttemptService;

    public ExamAttemptController(ExamAttemptService examAttemptService) {
        this.examAttemptService = examAttemptService;
    }

    @Operation(
            description = "Starts an attempt. 409 `code: EXAM_ACTIVE_ATTEMPT_EXISTS` when the learner already has an unfinished attempt on this paper.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "attempt created"),
                    @ApiResponse(responseCode = "409", description = "`code: EXAM_ACTIVE_ATTEMPT_EXISTS`")
            })
    @PostMapping(value = "/exams/{examId}/sessions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamAttemptView create(@PathVariable Long spaceId,
                                  @PathVariable Long examId,
                                  Authentication authentication) {
        ExamAttempt attempt = examAttemptService.create(
                authentication.getName(), spaceId, examId);
        return attemptView(attempt);
    }

    @GetMapping(value = "/exam-attempts/{attemptId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamAttemptView get(@PathVariable Long spaceId,
                               @PathVariable Long attemptId,
                               Authentication authentication) {
        ExamAttempt attempt = examAttemptService.getMine(
                authentication.getName(), spaceId, attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
        return attemptView(attempt);
    }

    @PostMapping(value = "/exam-attempts/{attemptId}/start",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamAttemptView start(@PathVariable Long spaceId,
                                 @PathVariable Long attemptId,
                                 Authentication authentication) {
        ExamAttempt attempt = examAttemptService.start(
                authentication.getName(), spaceId, attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
        return attemptView(attempt);
    }

    @Operation(
            description = "Saves one answer against the paper's frozen snapshot. Every rejection names a code: EXAM_ATTEMPT_NOT_IN_PROGRESS, EXAM_DEADLINE_EXCEEDED, EXAM_ANSWER_STALE (another request rewrote this answer), EXAM_ANSWER_NOT_GRADABLE.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "answer stored and graded"),
                    @ApiResponse(responseCode = "409", description = "conflict with a stable `code`")
            })
    @PostMapping(value = "/exam-attempts/{attemptId}/answers",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExamAnswerView answer(@PathVariable Long spaceId,
                                 @PathVariable Long attemptId,
                                 @Valid @RequestBody ExamAnswerRequest request,
                                 Authentication authentication) {
        return examAttemptService.answer(
                authentication.getName(), spaceId, attemptId, request);
    }

    @Operation(
            description = "Submits and grades in one transaction. 409 `code: EXAM_ATTEMPT_ALREADY_SUBMITTED` on a second submit, `code: EXAM_RESULT_NOT_AVAILABLE` when grading produced no result row.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "result of this submission"),
                    @ApiResponse(responseCode = "409", description = "conflict with a stable `code`")
            })
    @PostMapping(value = "/exam-attempts/{attemptId}/submit",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamResultView submit(@PathVariable Long spaceId,
                                 @PathVariable Long attemptId,
                                 Authentication authentication) {
        return examAttemptService.submit(
                authentication.getName(), spaceId, attemptId);
    }

    @Operation(
            description = "Reads the stored result. 409 `code: EXAM_RESULT_NOT_AVAILABLE` while the attempt is not submitted, `code: EXAM_RESULT_MISSING` when a submitted attempt has no result row to show.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "graded result"),
                    @ApiResponse(responseCode = "409", description = "conflict with a stable `code`")
            })
    @GetMapping(value = "/exam-attempts/{attemptId}/result",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamResultView result(@PathVariable Long spaceId,
                                 @PathVariable Long attemptId,
                                 Authentication authentication) {
        return examAttemptService.getResult(
                authentication.getName(), spaceId, attemptId);
    }

    /**
     * Manual subjective grading. Product rule: only ROLE_ADMIN may grade.
     * Ordinary examinees must never grade their own answers.
     */
    @PutMapping(value = "/exam-attempts/{attemptId}/answers/{answerId}/grade",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExamAnswerGradingView grade(@PathVariable Long spaceId,
                                       @PathVariable Long attemptId,
                                       @PathVariable Long answerId,
                                       @Valid @RequestBody GradeAnswerRequest request,
                                       Authentication authentication) {
        boolean isAdmin = authentication != null && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "ADMIN role required");
        }
        return examAttemptService.gradeAnswer(
                authentication.getName(), spaceId, attemptId, answerId, request);
    }

    /**
     * C11: paged history of the caller's own attempts, optionally narrowed to
     * one exam. Without it an attempt could only ever be opened from the exam
     * detail page that created it, so a previous attempt and its result were
     * unreachable from the UI.
     */
    @GetMapping(value = "/exam-attempts", produces = MediaType.APPLICATION_JSON_VALUE)
    public AttemptPageResponse listAttempts(
            @PathVariable Long spaceId,
            @RequestParam(required = false) Long examId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        ExamAttemptService.AttemptPage result = examAttemptService.listAttempts(
                authentication.getName(), spaceId, examId, status,
                Math.max(page, 0), safeSize);
        List<AttemptSummaryView> items = result.items().stream()
                .map(attempt -> AttemptSummaryView.from(
                        attempt, examAttemptService.resultOrNull(attempt)))
                .toList();
        int totalPages = (int) ((result.total() + safeSize - 1) / safeSize);
        return new AttemptPageResponse(items, result.page(), safeSize,
                result.total(), totalPages);
    }

    private ExamAttemptView attemptView(ExamAttempt attempt) {
        // C10: the attempt view carries the caller's own saved payloads, so a
        // reloaded exam page can rebuild which slots were already answered.
        return ExamAttemptView.from(attempt, examAttemptService.questionsOf(attempt),
                examAttemptService.answersOf(attempt));
    }
}
