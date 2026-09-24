package com.aistudy.server.exam.dto;

import com.aistudy.server.exam.entity.ExamAnswer;
import com.aistudy.server.exam.entity.ExamAttempt;
import com.aistudy.server.exam.entity.ExamQuestion;
import com.aistudy.server.exam.entity.ExamResult;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.eval.AnswerDataCodec.SnapshotView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-013 — typed Exam attempt DTOs.
 *
 * <p>Answer-secrecy contract (api-guidelines.md §9): the attempt
 * question view and the answer POST response carry NO correctness
 * fields; only the submit result view reveals isCorrect / correct
 * answer / explanation.
 */
public final class ExamAttemptDto {

    private ExamAttemptDto() {
    }

    /** Attempt detail: identity + status + SAFE paper question views. */
    public record ExamAttemptView(
            Long id,
            Long examId,
            Long examPaperId,
            String status,
            LocalDateTime startedAt,
            LocalDateTime deadlineAt,
            LocalDateTime submittedAt,
            List<AttemptQuestionView> questions
    ) {
        public static ExamAttemptView from(ExamAttempt attempt, List<ExamQuestion> slots) {
            return new ExamAttemptView(
                    attempt.getId(), attempt.getExamId(), attempt.getExamPaperId(),
                    attempt.getStatus(), attempt.getStartedAt(), attempt.getDeadlineAt(),
                    attempt.getSubmittedAt(),
                    slots == null ? List.of() : slots.stream()
                            .map(AttemptQuestionView::from)
                            .toList());
        }
    }

    /** One paper slot, SAFE view (no answer data). */
    public record AttemptQuestionView(
            Long examQuestionId,
            Long questionId,
            Integer sortOrder,
            Integer score,
            String questionType,
            String stem,
            List<OptionView> options
    ) {
        static AttemptQuestionView from(ExamQuestion slot) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            return new AttemptQuestionView(
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

    /** Request for {@code POST .../exam-attempts/{attemptId}/answers}. */
    public record ExamAnswerRequest(
            @NotNull(message = "examQuestionId must not be null")
            Long examQuestionId,

            @Valid
            @NotNull(message = "answer must not be null")
            AnswerPayloadView answer
    ) {

        /** Typed answer payload (exactly one field per question type). */
        public record AnswerPayloadView(
                @Size(max = 16, message = "at most 16 option keys allowed")
                List<@Size(max = 16, message = "option key too long") String> selectedOptionKeys,

                Boolean booleanAnswer,

                @Size(max = 4000, message = "textAnswer must be at most 4000 characters")
                String textAnswer,

                @Size(max = 100, message = "orderingAnswer must be at most 100 items")
                List<Integer> orderingAnswer,

                @Size(max = 10000, message = "matchingAnswer JSON must be at most 10000 characters")
                String matchingAnswer
        ) {
        }
    }

    /**
     * Answer POST response — deliberately WITHOUT isCorrect/score/
     * correctAnswer (leak-free until submit).
     */
    public record ExamAnswerView(
            Long id,
            Long examAttemptId,
            Long examQuestionId,
            String gradingStatus,
            LocalDateTime answeredAt
    ) {
        public static ExamAnswerView from(ExamAnswer answer) {
            return new ExamAnswerView(
                    answer.getId(), answer.getExamAttemptId(), answer.getExamQuestionId(),
                    answer.getGradingStatus(), answer.getAnsweredAt());
        }
    }

    /** Result after submit — correctness revealed here only. */
    public record ExamResultView(
            Long attemptId,
            String status,
            Integer score,
            Integer maxScore,
            Integer correctCount,
            Integer wrongCount,
            Integer unansweredCount,
            Integer durationMs,
            List<ExamResultItemView> items
    ) {
        public static ExamResultView from(ExamResult result, List<ExamResultItemView> items) {
            return new ExamResultView(
                    result.getExamAttemptId(), "SUBMITTED",
                    result.getScore(), result.getMaxScore(),
                    result.getCorrectCount(), result.getWrongCount(),
                    result.getUnansweredCount(), result.getDurationMs(),
                    items);
        }
    }

    /** Per-question result detail (post-submit only). */
    public record ExamResultItemView(
            Long examQuestionId,
            Long questionId,
            Integer score,
            Integer maxScore,
            Boolean isCorrect,
            String correctAnswer,
            String explanation,
            Boolean answered
    ) {
    }

    /** Request for manual grading of a subjective answer. */
    public record GradeAnswerRequest(
            @jakarta.validation.constraints.Min(value = 0, message = "score must be >= 0")
            Integer score,

            @Size(max = 2000, message = "feedback must be at most 2000 characters")
            String feedback
    ) {
    }

    /** Response after manual grading (includes score + audit trail). */
    public record ExamAnswerGradingView(
            Long id,
            Long examAttemptId,
            Long examQuestionId,
            String gradingStatus,
            LocalDateTime answeredAt,
            Integer score,
            String feedback,
            String gradedBy,
            LocalDateTime gradedAt
    ) {
        public static ExamAnswerGradingView from(ExamAnswer answer) {
            return new ExamAnswerGradingView(
                    answer.getId(), answer.getExamAttemptId(), answer.getExamQuestionId(),
                    answer.getGradingStatus(), answer.getAnsweredAt(),
                    answer.getScore(), answer.getFeedback(),
                    answer.getGradedBy(), answer.getGradedAt());
        }
    }
}
