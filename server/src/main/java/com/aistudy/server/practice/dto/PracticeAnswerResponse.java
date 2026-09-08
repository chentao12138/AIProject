package com.aistudy.server.practice.dto;

import com.aistudy.server.practice.entity.PracticeAnswer;

import java.time.LocalDateTime;

/**
 * BUSINESS-010 — typed practice answer responses.
 *
 * <p>Practice grants IMMEDIATE feedback (ADR-041): the answer
 * response returns isCorrect / correctAnswer / explanation right
 * after answering. SHORT_ANSWER answers are stored ungraded
 * (isCorrect/score/explanation null).
 */
public final class PracticeAnswerResponse {

    private PracticeAnswerResponse() {
    }

    /** Answer + feedback for the answered question. */
    public record PracticeAnswerView(
            Long id,
            Long practiceSessionQuestionId,
            Long questionId,
            Boolean isCorrect,
            String correctAnswer,
            String explanation,
            Integer score,
            LocalDateTime answeredAt
    ) {
        public static PracticeAnswerView from(PracticeAnswer answer,
                                              String correctAnswer,
                                              String explanation) {
            return new PracticeAnswerView(
                    answer.getId(),
                    answer.getPracticeSessionQuestionId(),
                    answer.getQuestionId(),
                    answer.getIsCorrect(),
                    correctAnswer,
                    explanation,
                    answer.getScore(),
                    answer.getSubmittedAt());
        }
    }

    /** Submit summary (finish): objective score + coverage counts. */
    public record PracticeSubmitView(
            Long sessionId,
            String status,
            Integer questionCount,
            Integer answeredCount,
            Integer correctCount,
            Integer score,
            Integer maxScore
    ) {
        public static PracticeSubmitView from(Long sessionId,
                                              int questionCount,
                                              int answeredCount,
                                              int correctCount,
                                              int score,
                                              int maxScore) {
            return new PracticeSubmitView(sessionId, "SUBMITTED",
                    questionCount, answeredCount, correctCount, score, maxScore);
        }
    }
}
