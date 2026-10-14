package com.aistudy.server.admin.exam.controller;

import com.aistudy.server.ai.grading.AiGradingSuggestionService;
import com.aistudy.server.ai.grading.AiGradingSuggestionService.GradingSuggestionView;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.exam.dto.ExamAttemptDto.AdminGradingView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerGradingView;
import com.aistudy.server.exam.dto.ExamAttemptDto.GradeAnswerRequest;
import com.aistudy.server.exam.entity.Exam;
import com.aistudy.server.exam.entity.ExamAttempt;
import com.aistudy.server.exam.entity.ExamPaper;
import com.aistudy.server.exam.mapper.ExamAttemptMapper;
import com.aistudy.server.exam.mapper.ExamMapper;
import com.aistudy.server.exam.mapper.ExamPaperMapper;
import com.aistudy.server.exam.service.ExamAttemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/exams")
@SecurityRequirement(name = "bearerAuth")
public class AdminExamController {

    private final ExamMapper examMapper;
    private final ExamPaperMapper examPaperMapper;
    private final ExamAttemptMapper examAttemptMapper;
    private final ExamAttemptService examAttemptService;
    private final AiGradingSuggestionService aiGradingSuggestionService;

    public AdminExamController(ExamMapper examMapper,
                               ExamPaperMapper examPaperMapper,
                               ExamAttemptMapper examAttemptMapper,
                               ExamAttemptService examAttemptService,
                               AiGradingSuggestionService aiGradingSuggestionService) {
        this.examMapper = examMapper;
        this.examPaperMapper = examPaperMapper;
        this.examAttemptMapper = examAttemptMapper;
        this.examAttemptService = examAttemptService;
        this.aiGradingSuggestionService = aiGradingSuggestionService;
    }

    public record AdminExamView(
            Long id, Long spaceId, String title, String description,
            String examType, Integer timeLimitMinutes, Integer totalScore,
            String status, String createdByUserId,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDateTime publishedAt, LocalDateTime archivedAt) {
        public static AdminExamView from(Exam exam) {
            return new AdminExamView(
                    exam.getId(), exam.getSpaceId(), exam.getTitle(), exam.getDescription(),
                    exam.getExamType(), exam.getTimeLimitMinutes(), exam.getTotalScore(),
                    exam.getStatus(), exam.getCreatedByUserId(),
                    exam.getCreatedAt(), exam.getUpdatedAt(),
                    exam.getPublishedAt(), exam.getArchivedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminExamView> list(
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) String status) {
        List<Exam> exams = examMapper.selectAllAdmin(spaceId, status);
        return exams.stream().map(AdminExamView::from).toList();
    }

    @GetMapping("/{examId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminExamView get(@PathVariable Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Exam not found");
        }
        return AdminExamView.from(exam);
    }

    @PostMapping("/{examId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminExamView publish(@PathVariable Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Exam not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ExamPaper paper = examPaperMapper.selectLatestByExam(exam.getSpaceId(), exam.getId());
        int paperUpdated = 0;
        if (paper != null) {
            paperUpdated = examPaperMapper.updateStatusByIdAndSpace(
                    paper.getId(), exam.getSpaceId(), "DRAFT", "PUBLISHED", now);
        }
        if (paper == null || paperUpdated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "exam paper is not in DRAFT state");
        }
        int updated = examMapper.publishByIdAndSpace(
                examId, exam.getSpaceId(), "DRAFT", "PUBLISHED", now, now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "exam state changed concurrently");
        }
        exam.setStatus("PUBLISHED");
        exam.setPublishedAt(now);
        exam.setUpdatedAt(now);
        return AdminExamView.from(exam);
    }

    @PostMapping("/{examId}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminExamView archive(@PathVariable Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Exam not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = examMapper.archiveByIdAndSpace(examId, exam.getSpaceId(), "ARCHIVED", now, now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "Exam state changed concurrently");
        }
        exam.setStatus("ARCHIVED");
        exam.setArchivedAt(now);
        exam.setUpdatedAt(now);
        return AdminExamView.from(exam);
    }

    @GetMapping("/{examId}/attempts")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ExamAttempt> listAttempts(@PathVariable Long examId) {
        return examAttemptMapper.selectByExamId(examId);
    }

    /**
     * The grading queue of one attempt: every stored answer with the slot's
     * question type, max score and the text the learner wrote.
     */
    @Operation(description = "Answers of one attempt as a grader sees them. 404 "
            + "`code: EXAM_NOT_FOUND` when the exam is unknown, `code: EXAM_ANSWER_NOT_FOUND` "
            + "for an attempt that is not part of it.")
    @GetMapping(value = "/{examId}/attempts/{attemptId}/answers",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public List<ExamAnswerGradingView> gradingQueue(@PathVariable Long examId,
                                                    @PathVariable Long attemptId) {
        return examAttemptService.gradingQueue(spaceOf(examId).getSpaceId(), examId, attemptId);
    }

    /**
     * Grade — or correct the grade of — one subjective answer of any learner's
     * attempt (E-04). Writes the score through the domain service, so the result,
     * the diagnosis and the mastery evidence are recomputed from it.
     */
    @Operation(description = "ADMIN manual grade of one subjective answer. The score is stored and"
            + " result/diagnosis/mastery are recomputed from it, and grading_status only reaches"
            + " GRADED once no stored subjective answer awaits a score. 400 when the score is"
            + " outside 0..slot maxScore; 404 `code: EXAM_ANSWER_NOT_FOUND`; 409"
            + " `code: EXAM_ANSWER_NOT_SUBJECTIVE` for an objective slot, `code:"
            + " EXAM_GRADING_INCOMPLETE`/`code: EXAM_RESULT_NOT_AVAILABLE` for the attempt state.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "answer graded"),
                    @ApiResponse(responseCode = "409", description = "`code:"
                            + " EXAM_ANSWER_NOT_SUBJECTIVE` or `code: EXAM_ANSWER_STALE`")
            })
    @PutMapping(value = "/{examId}/attempts/{attemptId}/answers/{answerId}/grade",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ExamAnswerGradingView gradeAnswer(@PathVariable Long examId,
                                             @PathVariable Long attemptId,
                                             @PathVariable Long answerId,
                                             @Valid @RequestBody GradeAnswerRequest request,
                                             Authentication authentication) {
        return examAttemptService.gradeAnswerAsAdmin(authentication.getName(),
                spaceOf(examId).getSpaceId(), examId, attemptId, answerId, request);
    }

    /**
     * Finalize grading (E-04). Reports the verdict the answer rows actually
     * support instead of asserting one: it 409s while an answer still awaits a
     * grade, which is what the previous version of this endpoint skipped by
     * writing {@code grading_status = 'GRADED'} over ungraded work.
     */
    @Operation(description = "Recomputes the attempt result from the stored grades and returns the"
            + " verdict. 409 `code: EXAM_GRADING_INCOMPLETE` while a subjective answer still awaits"
            + " a score; `code: EXAM_RESULT_NOT_AVAILABLE` before submit.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "grading finalized"),
                    @ApiResponse(responseCode = "409", description = "`code:"
                            + " EXAM_GRADING_INCOMPLETE`")
            })
    @PostMapping(value = "/{examId}/attempts/{attemptId}/grade",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public AdminGradingView finalizeGrading(@PathVariable Long examId,
                                            @PathVariable Long attemptId,
                                            Authentication authentication) {
        return examAttemptService.finalizeGradingAsAdmin(authentication.getName(),
                spaceOf(examId).getSpaceId(), examId, attemptId);
    }

    /**
     * Read-only AI grading suggestion (E-07). It never stores a score: an ADMIN
     * has to post it through the grading endpoint for it to count.
     */
    @Operation(description = "Suggested score and rationale for one subjective answer, produced by"
            + " the configured AI provider. Advisory only: nothing is written, final_score is always"
            + " false, and a score outside 0..maxScore is clamped with clamped=true. 404 `code:"
            + " EXAM_ANSWER_NOT_FOUND`; 409 `code: AI_GRADING_SUGGESTION_NOT_AVAILABLE` for a"
            + " non-subjective slot or an unusable model response; 502 `code:"
            + " AI_PROVIDER_UNAVAILABLE`.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "suggestion produced"),
                    @ApiResponse(responseCode = "409", description = "`code:"
                            + " AI_GRADING_SUGGESTION_NOT_AVAILABLE`")
            })
    @PostMapping(value = "/{examId}/attempts/{attemptId}/answers/{answerId}/grading-suggestion",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public GradingSuggestionView gradingSuggestion(@PathVariable Long examId,
                                                   @PathVariable Long attemptId,
                                                   @PathVariable Long answerId,
                                                   Authentication authentication) {
        return aiGradingSuggestionService.suggest(authentication.getName(),
                spaceOf(examId).getSpaceId(), examId, attemptId, answerId);
    }

    private Exam spaceOf(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw ApiException.notFound(ApiErrorCodes.EXAM_NOT_FOUND, "Exam not found");
        }
        return exam;
    }
}
