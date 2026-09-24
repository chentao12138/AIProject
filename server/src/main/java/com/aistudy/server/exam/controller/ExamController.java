package com.aistudy.server.exam.controller;

import com.aistudy.server.exam.dto.ExamDto.CreateExamRequest;
import com.aistudy.server.exam.dto.ExamDto.ExamResponse;
import com.aistudy.server.exam.dto.UpdateExamRequest;
import com.aistudy.server.exam.entity.Exam;
import com.aistudy.server.exam.entity.ExamPaper;
import com.aistudy.server.exam.entity.ExamQuestion;
import com.aistudy.server.exam.mapper.ExamQuestionMapper;
import com.aistudy.server.exam.service.ExamService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace or Question not found");
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

    @PutMapping(value = "/{examId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamResponse update(@PathVariable Long spaceId,
                               @PathVariable Long examId,
                               @Valid @RequestBody UpdateExamRequest request,
                               Authentication authentication) {
        Exam updated = examService.update(authentication.getName(), spaceId, examId, request);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        return toResponse(updated);
    }

    @PostMapping(value = "/{examId}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamResponse archive(@PathVariable Long spaceId,
                                @PathVariable Long examId,
                                Authentication authentication) {
        Exam updated = examService.archive(authentication.getName(), spaceId, examId);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        return toResponse(updated);
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

    // ==================== draft paper composition ====================

    public record AddQuestionRequest(
            @jakarta.validation.constraints.NotNull(message = "questionId must not be null")
            Long questionId,

            @jakarta.validation.constraints.Min(value = 1, message = "score must be >= 1")
            Integer score
    ) {
    }

    @PostMapping(value = "/{examId}/paper/questions",
            produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExamQuestion addQuestion(@PathVariable Long spaceId,
                                    @PathVariable Long examId,
                                    @Valid @RequestBody AddQuestionRequest request,
                                    Authentication authentication) {
        ExamQuestion slot = examService.addQuestionToPaper(
                authentication.getName(), spaceId, examId,
                request.questionId(), request.score());
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        return slot;
    }

    @DeleteMapping(value = "/{examId}/paper/questions/{examQuestionId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamQuestion removeQuestion(@PathVariable Long spaceId,
                                       @PathVariable Long examId,
                                       @PathVariable Long examQuestionId,
                                       Authentication authentication) {
        ExamQuestion slot = examService.removeQuestionFromPaper(
                authentication.getName(), spaceId, examId, examQuestionId);
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        return slot;
    }

    @PutMapping(value = "/{examId}/paper/questions/reorder",
            produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<ExamQuestion> reorder(@PathVariable Long spaceId,
                                      @PathVariable Long examId,
                                      @Valid @RequestBody List<ExamQuestionMapper.SortSlot> slots,
                                      Authentication authentication) {
        List<ExamQuestion> result = examService.reorderQuestions(
                authentication.getName(), spaceId, examId, slots);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        return result;
    }

    @PutMapping(value = "/{examId}/paper/questions/{examQuestionId}/score",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamQuestion updateScore(@PathVariable Long spaceId,
                                    @PathVariable Long examId,
                                    @PathVariable Long examQuestionId,
                                    @RequestParam(name = "score") Integer score,
                                    Authentication authentication) {
        if (score == null || score < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be >= 1");
        }
        ExamQuestion slot = examService.updateQuestionScore(
                authentication.getName(), spaceId, examId, examQuestionId, score);
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        return slot;
    }

    private ExamResponse toResponse(Exam exam) {
        ExamPaper paper = examService.paperOf(exam);
        return ExamResponse.from(exam, paper,
                paper == null ? List.of() : examService.questionsOf(paper));
    }
}
