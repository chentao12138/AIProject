package com.aistudy.server.exam.controller;

import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerGradingView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerRequest;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAttemptView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamResultView;
import com.aistudy.server.exam.dto.ExamAttemptDto.GradeAnswerRequest;
import com.aistudy.server.exam.entity.ExamAttempt;
import com.aistudy.server.exam.service.ExamAttemptService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

    @PostMapping(value = "/exam-attempts/{attemptId}/submit",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamResultView submit(@PathVariable Long spaceId,
                                 @PathVariable Long attemptId,
                                 Authentication authentication) {
        return examAttemptService.submit(
                authentication.getName(), spaceId, attemptId);
    }

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

    private ExamAttemptView attemptView(ExamAttempt attempt) {
        return ExamAttemptView.from(attempt, examAttemptService.questionsOf(attempt));
    }
}
