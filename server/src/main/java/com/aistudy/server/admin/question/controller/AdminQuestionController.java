package com.aistudy.server.admin.question.controller;

import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.admin.bulk.service.BulkOperationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/questions")
@SecurityRequirement(name = "bearerAuth")
public class AdminQuestionController {

    private final QuestionMapper questionMapper;

    public AdminQuestionController(QuestionMapper questionMapper) {
        this.questionMapper = questionMapper;
    }

    public record AdminQuestionView(
            Long id, Long spaceId, String questionType, String stem,
            String explanation, String difficulty, String originType,
            String status, String createdByUserId,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDateTime publishedAt, LocalDateTime archivedAt) {
        public static AdminQuestionView from(Question q) {
            return new AdminQuestionView(
                    q.getId(), q.getSpaceId(), q.getQuestionType(), q.getStem(),
                    q.getExplanation(), q.getDifficulty(), q.getOriginType(),
                    q.getStatus(), q.getCreatedByUserId(),
                    q.getCreatedAt(), q.getUpdatedAt(),
                    q.getPublishedAt(), q.getArchivedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminQuestionView> list(
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String questionType) {
        List<Question> questions = questionMapper.selectAllAdmin(spaceId, status, questionType);
        return questions.stream().map(AdminQuestionView::from).toList();
    }

    @GetMapping("/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminQuestionView get(@PathVariable Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Question not found");
        }
        return AdminQuestionView.from(question);
    }

    @PostMapping("/{questionId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminQuestionView publish(@PathVariable Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Question not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = questionMapper.publishByIdAndSpace(
                questionId, question.getSpaceId(), "PUBLISHED", now, now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "Question state changed concurrently");
        }
        question.setStatus("PUBLISHED");
        question.setPublishedAt(now);
        question.setUpdatedAt(now);
        return AdminQuestionView.from(question);
    }

    @PostMapping("/{questionId}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminQuestionView archive(@PathVariable Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Question not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = questionMapper.archiveByIdAndSpace(
                questionId, question.getSpaceId(), "ARCHIVED", now, now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "Question state changed concurrently");
        }
        question.setStatus("ARCHIVED");
        question.setArchivedAt(now);
        question.setUpdatedAt(now);
        return AdminQuestionView.from(question);
    }

    @PostMapping("/bulk-publish")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkPublish(@RequestBody List<Long> questionIds) {
        return BulkOperationService.bulkPublishQuestions(questionMapper, questionIds);
    }

    @PostMapping("/bulk-archive")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkArchive(@RequestBody List<Long> questionIds) {
        return BulkOperationService.bulkArchiveQuestions(questionMapper, questionIds);
    }

    public record BulkResult(int total, int success, int failed, List<String> errors) {
    }
}
