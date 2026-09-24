package com.aistudy.server.exam.dto;

import com.aistudy.server.exam.entity.Exam;
import com.aistudy.server.exam.entity.ExamPaper;
import com.aistudy.server.exam.entity.ExamQuestion;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.eval.AnswerDataCodec.SnapshotView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-012 — typed Exam DTOs.
 *
 * <p>The authoring response carries the exam + its fixed composition
 * as SAFE question views (no answerData / no option correctness) —
 * exam question content always goes through the attempt flow where
 * answer secrecy is enforced (api-guidelines.md §9).
 */
public final class ExamDto {

    private ExamDto() {
    }

    /** Request for {@code POST /api/v1/spaces/{spaceId}/exams}. */
    public record CreateExamRequest(
            @NotBlank(message = "title must not be blank")
            @Size(max = 255, message = "title must be at most 255 characters")
            String title,

            @Size(max = 1000, message = "description must be at most 1000 characters")
            String description,

            @Size(max = 32, message = "examType must be at most 32 characters")
            String examType,

            @Min(value = 1, message = "timeLimitMinutes must be >= 1")
            Integer timeLimitMinutes,

            @NotEmpty(message = "questions must not be empty")
            @Size(max = 100, message = "at most 100 questions per exam")
            @Valid
            List<ExamQuestionInput> questions
    ) {
    }

    /** One scored question slot. */
    public record ExamQuestionInput(
            @jakarta.validation.constraints.NotNull(message = "questionId must not be null")
            Long questionId,

            @Min(value = 1, message = "score must be >= 1")
            Integer score
    ) {
    }

    /** Exam authoring response. */
    public record ExamResponse(
            Long id,
            Long spaceId,
            String title,
            String description,
            String examType,
            Integer timeLimitMinutes,
            Integer totalScore,
            String status,
            Integer paperVersion,
            List<ExamQuestionView> questions,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime publishedAt
    ) {
        public static ExamResponse from(Exam exam, ExamPaper paper,
                                        List<ExamQuestion> questions) {
            return new ExamResponse(
                    exam.getId(), exam.getSpaceId(), exam.getTitle(),
                    exam.getDescription(), exam.getExamType(),
                    exam.getTimeLimitMinutes(), exam.getTotalScore(),
                    exam.getStatus(),
                    paper == null ? null : paper.getPaperVersion(),
                    questions == null ? List.of() : questions.stream()
                            .map(ExamQuestionView::from)
                            .toList(),
                    exam.getCreatedAt(), exam.getUpdatedAt(), exam.getPublishedAt());
        }
    }

    /** One composition slot, SAFE view (no answer data). */
    public record ExamQuestionView(
            Long examQuestionId,
            Long questionId,
            Integer sortOrder,
            Integer score,
            String questionType,
            String stem,
            List<OptionView> options
    ) {
        static ExamQuestionView from(ExamQuestion slot) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            return new ExamQuestionView(
                    slot.getId(), slot.getQuestionId(), slot.getSortOrder(), slot.getScore(),
                    snapshot.questionType(), snapshot.stem(),
                    snapshot.options().stream()
                            .map(o -> new OptionView(
                                    String.valueOf(o.get("optionKey")),
                                    String.valueOf(o.get("content")),
                                    o.get("sortOrder") == null ? null
                                            : ((Number) o.get("sortOrder")).intValue()))
                            .toList());
        }
    }

    /** Option without any correctness flag. */
    public record OptionView(String optionKey, String content, Integer sortOrder) {
    }
}
