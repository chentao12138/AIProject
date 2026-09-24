package com.aistudy.server.admin.exam.controller;

import com.aistudy.server.exam.entity.Exam;
import com.aistudy.server.exam.entity.ExamAttempt;
import com.aistudy.server.exam.entity.ExamPaper;
import com.aistudy.server.exam.mapper.ExamAnswerMapper;
import com.aistudy.server.exam.mapper.ExamAttemptMapper;
import com.aistudy.server.exam.mapper.ExamMapper;
import com.aistudy.server.exam.mapper.ExamPaperMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final ExamAnswerMapper examAnswerMapper;

    public AdminExamController(ExamMapper examMapper,
                               ExamPaperMapper examPaperMapper,
                               ExamAttemptMapper examAttemptMapper,
                               ExamAnswerMapper examAnswerMapper) {
        this.examMapper = examMapper;
        this.examPaperMapper = examPaperMapper;
        this.examAttemptMapper = examAttemptMapper;
        this.examAnswerMapper = examAnswerMapper;
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

    @PostMapping("/{examId}/attempts/{attemptId}/grade")
    @PreAuthorize("hasRole('ADMIN')")
    public String grade(@PathVariable Long examId, @PathVariable Long attemptId) {
        ExamAttempt attempt = examAttemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
        List<com.aistudy.server.exam.entity.ExamAnswer> answers =
                examAnswerMapper.selectByAttemptId(attempt.getSpaceId(), attemptId);
        boolean anyUngraded = answers.stream()
                .anyMatch(a -> "UNGRADED".equals(a.getGradingStatus()));
        if (anyUngraded) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "attempt contains ungraded SHORT_ANSWER items");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = examAttemptMapper.updateGradingStatusByIdAndSpace(
                attemptId, attempt.getSpaceId(), "GRADED", now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "attempt state changed concurrently");
        }
        return "graded";
    }
}
