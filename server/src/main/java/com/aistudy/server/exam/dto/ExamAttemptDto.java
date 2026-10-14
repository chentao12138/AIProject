package com.aistudy.server.exam.dto;

import com.aistudy.server.exam.entity.ExamAnswer;
import com.aistudy.server.exam.entity.ExamAttempt;
import com.aistudy.server.exam.entity.ExamQuestion;
import com.aistudy.server.exam.entity.ExamResult;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.eval.AnswerDataCodec.SnapshotView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
            return from(attempt, slots, List.of());
        }

        /**
         * @param answers every answer the caller already stored for this
         *                attempt; each is echoed onto its own slot so a
         *                reloaded exam page shows what was saved (C10)
         */
        public static ExamAttemptView from(ExamAttempt attempt, List<ExamQuestion> slots,
                                           List<ExamAnswer> answers) {
            java.util.Map<Long, ExamAnswer> bySlot = new java.util.HashMap<>();
            for (ExamAnswer answer : answers) {
                bySlot.put(answer.getExamQuestionId(), answer);
            }
            return new ExamAttemptView(
                    attempt.getId(), attempt.getExamId(), attempt.getExamPaperId(),
                    attempt.getStatus(), attempt.getStartedAt(), attempt.getDeadlineAt(),
                    attempt.getSubmittedAt(),
                    slots == null ? List.of() : slots.stream()
                            .map(slot -> AttemptQuestionView.from(slot, bySlot.get(slot.getId())))
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
            List<OptionView> options,
            ExamAnswerRequest.AnswerPayloadView answer,
            boolean answered
    ) {
        static AttemptQuestionView from(ExamQuestion slot) {
            return from(slot, null);
        }

        /**
         * @param slot  the frozen paper slot
         * @param saved the caller's own stored answer for this slot, or
         *              {@code null} while it is still unanswered
         *
         * <p>Only what the candidate submitted is echoed. The correct answer,
         * score and explanation stay exclusive to the result view, so a reload
         * can rebuild the form (C10) without revealing grading mid-exam.
         */
        static AttemptQuestionView from(ExamQuestion slot, ExamAnswer saved) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            AnswerDataCodec.AnswerPayloadView stored = saved == null
                    ? null
                    : AnswerDataCodec.parseAnswerPayload(saved.getAnswerDataJson());
            return new AttemptQuestionView(
                    slot.getId(), slot.getQuestionId(), slot.getSortOrder(), slot.getScore(),
                    snapshot.questionType(), snapshot.stem(),
                    snapshot.options().stream()
                            .map(o -> new OptionView(
                                    String.valueOf(o.get("optionKey")),
                                    String.valueOf(o.get("content")),
                                    o.get("sortOrder") == null ? null
                                            : ((Number) o.get("sortOrder")).intValue()))
                            .toList(),
                    stored == null ? null
                            : new ExamAnswerRequest.AnswerPayloadView(
                                    stored.selectedOptionKeys(), stored.booleanAnswer(),
                                    stored.textAnswer(), stored.orderingAnswer(),
                                    stored.matchingAnswer()),
                    saved != null);
        }
    }

    /** Option without any correctness flag. */
    public record OptionView(String optionKey, String content, Integer sortOrder) {
    }

    /**
     * One row of the attempt history (C11).
     *
     * <p>{@code score}/{@code maxScore} stay {@code null} until the attempt has
     * a persisted result: an unfinished attempt has no score, and 0 would be a
     * fabricated grade that the client cannot tell apart from a real zero.
     *
     * <p>{@code gradingStatus} is carried because a score alone cannot say
     * whether it is final: a partially graded attempt already has a real score
     * that is not the whole grade, and the client must label the row instead of
     * inferring "a number arrived" (contract request C11).
     */
    public record AttemptSummaryView(
            Long id,
            Long examId,
            String status,
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "Grading state of the attempt, null while it is still open. "
                            + "GRADED is final; PARTIALLY_GRADED means the objective part is scored "
                            + "while some answers still await a human grade.",
                    allowableValues = {"GRADED", "PARTIALLY_GRADED", "UNGRADED"})
            String gradingStatus,
            LocalDateTime startedAt,
            LocalDateTime submittedAt,
            Integer score,
            Integer maxScore
    ) {
        public static AttemptSummaryView from(ExamAttempt attempt, ExamResult result) {
            return new AttemptSummaryView(
                    attempt.getId(), attempt.getExamId(), attempt.getStatus(),
                    attempt.getGradingStatus(),
                    attempt.getStartedAt(), attempt.getSubmittedAt(),
                    result == null ? null : result.getScore(),
                    result == null ? null : result.getMaxScore());
        }
    }

    /** Paged attempt history, sharing the §11 envelope shape. */
    public record AttemptPageResponse(
            List<AttemptSummaryView> items,
            int page,
            int pageSize,
            long totalItems,
            int totalPages
    ) {
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
                List<@Min(value = 1, message = "orderingAnswer entries are 1-based option positions")
                     Integer> orderingAnswer,

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
            LocalDateTime answeredAt,
            ExamAnswerRequest.AnswerPayloadView answer,
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "Whether the OWNING ATTEMPT is already submitted, i.e. whether this"
                        + " stored answer is now read-only instead of re-writable. It says nothing"
                        + " about whether the answer itself was saved — a stored answer always comes"
                        + " back with the payload it persisted, and false here means the learner can"
                        + " still change it.")
            boolean submitted
    ) {
        public static ExamAnswerView from(ExamAnswer answer) {
            return from(answer, false);
        }

        /**
         * @param submitted whether the owning attempt is already submitted,
         *                  which is what tells the client the answer is now
         *                  read-only instead of editable
         */
        public static ExamAnswerView from(ExamAnswer answer, boolean submitted) {
            AnswerDataCodec.AnswerPayloadView stored =
                    AnswerDataCodec.parseAnswerPayload(answer.getAnswerDataJson());
            return new ExamAnswerView(
                    answer.getId(), answer.getExamAttemptId(), answer.getExamQuestionId(),
                    answer.getGradingStatus(), answer.getAnsweredAt(),
                    stored == null ? null
                            : new ExamAnswerRequest.AnswerPayloadView(
                                    stored.selectedOptionKeys(), stored.booleanAnswer(),
                                    stored.textAnswer(), stored.orderingAnswer(),
                                    stored.matchingAnswer()),
                    submitted);
        }
    }

    /**
     * Result after submit — correctness revealed here only.
     *
     * <p>{@code gradingStatus} and {@code pendingGradeCount} travel with the
     * score because a partially graded paper is not a final one: without them the
     * result page showed "5 / 6" with no way to say that a subjective answer was
     * still awaiting a human grade, so a provisional number read as the verdict.
     */
    public record ExamResultView(
            Long attemptId,
            String status,
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "GRADED is final. PARTIALLY_GRADED means the objective part is"
                        + " scored and pendingGradeCount subjective answers still await a human grade,"
                        + " so the score is provisional.")
            String gradingStatus,
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "Stored subjective answers still awaiting a human grade. More than"
                        + " zero means score is provisional; gradingStatus is PARTIALLY_GRADED.")
            int pendingGradeCount,
            Integer score,
            Integer maxScore,
            Integer correctCount,
            Integer wrongCount,
            Integer unansweredCount,
            Integer durationMs,
            List<ExamResultItemView> items
    ) {
        public static ExamResultView from(ExamResult result, List<ExamResultItemView> items,
                                          String gradingStatus, int pendingGradeCount) {
            return new ExamResultView(
                    result.getExamAttemptId(), "SUBMITTED", gradingStatus, pendingGradeCount,
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

    /**
     * One answer as the grading reviewer sees it, and only as the grading reviewer
     * sees it: {@code referenceAnswer} and {@code explanation} are the rubric the
     * grade is supposed to be against, and both stay on this ADMIN surface — the
     * learner's result view has its own reveal rules and is not built from here.
     * Without them a grader would read the stem and the candidate's text and
     * judge from intuition, which is barely better than the blind status flip this
     * endpoint replaces.
     *
     * <p>{@code previousScore}/{@code previousFeedback} are the first written
     * grade, kept when a grade is corrected, so a regrade is auditable.
     */
    public record ExamAnswerGradingView(
            Long id,
            Long examAttemptId,
            Long examQuestionId,
            String questionType,
            String stem,
            Integer maxScore,
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "ADMIN-only: the answer the frozen snapshot carries, shown so a"
                        + " human grade can be against a rubric rather than a guess. Never returned to"
                        + " the examinee.")
            String referenceAnswer,
            @io.swagger.v3.oas.annotations.media.Schema(description = "ADMIN-only explanation of the"
                    + " frozen snapshot.")
            String explanation,
            String answerText,
            String gradingStatus,
            LocalDateTime answeredAt,
            Integer score,
            Boolean isCorrect,
            String feedback,
            Integer previousScore,
            String previousFeedback,
            String gradedBy,
            LocalDateTime gradedAt
    ) {
        public static ExamAnswerGradingView from(ExamAnswer answer, ExamQuestion slot) {
            SnapshotView snapshot = slot == null ? null
                    : AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            return new ExamAnswerGradingView(
                    answer.getId(), answer.getExamAttemptId(), answer.getExamQuestionId(),
                    snapshot == null ? null : snapshot.questionType(),
                    snapshot == null ? null : snapshot.stem(),
                    slot == null ? null : slot.getScore(),
                    snapshot == null || snapshot.answerData() == null
                            ? null : snapshot.answerData().referenceAnswer(),
                    snapshot == null ? null : snapshot.explanation(),
                    AnswerDataCodec.parseAnswerPayload(answer.getAnswerDataJson()).textAnswer(),
                    answer.getGradingStatus(), answer.getAnsweredAt(),
                    answer.getScore(), answer.getIsCorrect(),
                    answer.getFeedback(),
                    answer.getPreviousScore(), answer.getPreviousFeedback(),
                    answer.getGradedBy(), answer.getGradedAt());
        }
    }

    /**
     * What an attempt scores once ADMIN grading is finalized. Reported from the
     * answer rows, not from a status flip: {@code gradingStatus} is GRADED only
     * when no stored subjective answer still awaits a score, and
     * {@code pendingGradeCount} is what stood in the way when it does not.
     */
    public record AdminGradingView(
            Long attemptId,
            Long examId,
            String status,
            String gradingStatus,
            Integer score,
            Integer maxScore,
            int pendingGradeCount
    ) {
    }
}
