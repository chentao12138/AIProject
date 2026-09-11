package com.aistudy.server.exam.controller;

import com.aistudy.server.exam.dto.ExamDto.CreateExamRequest;
import com.aistudy.server.exam.dto.ExamDto.ExamResponse;
import com.aistudy.server.exam.entity.Exam;
import com.aistudy.server.exam.entity.ExamPaper;
import com.aistudy.server.exam.service.ExamService;
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
 * BUSINESS-012 — Exam definition REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/exams              → 201 authoring view
 *   GET  /api/v1/spaces/{spaceId}/exams              → 200 typed list
 *   GET  /api/v1/spaces/{spaceId}/exams/{examId}     → 200 / 404
 *   POST /api/v1/spaces/{spaceId}/exams/{examId}/publish → 200
 *        (exam + paper DRAFT → PUBLISHED; idempotent)
 * </pre>
 *
 * <p>Owner-scoped, 404 anti-probing. Question views in responses are
 * SAFE (no answer data) — attempts (013) carry the answer-secrecy
 * contract.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/exams")
@SecurityRequirement(name = "bearerAuth")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExamResponse create(@PathVariable Long spaceId,
                               @Valid @RequestBody CreateExamRequest request,
                               Authentication authentication) {
        Exam exam = examService.create(authentication.getName(), spaceId, request);
        if (exam == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "LearningSpace or Question not found");
        }
        return toResponse(exam);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ExamResponse> list(@PathVariable Long spaceId,
                                   Authentication authentication) {
        List<Exam> exams = examService.listMine(authentication.getName(), spaceId);
        if (exams == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return exams.stream().map(this::toResponse).toList();
    }

    @GetMapping(value = "/{examId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamResponse get(@PathVariable Long spaceId,
                            @PathVariable Long examId,
                            Authentication authentication) {
        Exam exam = examService.getMine(authentication.getName(), spaceId, examId);
        if (exam == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        return toResponse(exam);
    }

    @PostMapping(value = "/{examId}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamResponse publish(@PathVariable Long spaceId,
                                @PathVariable Long examId,
                                Authentication authentication) {
        Exam exam = examService.publish(authentication.getName(), spaceId, examId);
        if (exam == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        return toResponse(exam);
    }

    private ExamResponse toResponse(Exam exam) {
        ExamPaper paper = examService.paperOf(exam);
        return ExamResponse.from(exam, paper,
                paper == null ? List.of() : examService.questionsOf(paper));
    }
}
