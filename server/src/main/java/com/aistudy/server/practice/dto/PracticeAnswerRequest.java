package com.aistudy.server.practice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * BUSINESS-010 — request body for
 * {@code POST /api/v1/spaces/{spaceId}/practice-sessions/{sessionId}/answers}.
 *
 * <p>Typed flat contract; the shape is validated by the service against
 * the session snapshot's question type (mutually exclusive fields):
 *
 * <pre>
 *   SINGLE_CHOICE    answer.selectedOptionKeys exactly 1 entry
 *   MULTIPLE_CHOICE  answer.selectedOptionKeys >= 1 entry (exact-set)
 *   TRUE_FALSE       answer.booleanAnswer
 *   SHORT_ANSWER     answer.textAnswer (stored, ungraded in V1)
 * </pre>
 *
 * @param practiceSessionQuestionId the fixed slot id from the session detail
 * @param answer                    typed answer payload
 * @param durationMs                optional client-measured duration
 */
public record PracticeAnswerRequest(
        @NotNull(message = "practiceSessionQuestionId must not be null")
        Long practiceSessionQuestionId,

        @Valid
        @NotNull(message = "answer must not be null")
        AnswerPayloadView answer,

        Integer durationMs
) {

    /** Typed answer payload (exactly one field per question type). */
    public record AnswerPayloadView(
            @Size(max = 16, message = "at most 16 option keys allowed")
            List<@Size(max = 16, message = "option key too long") String> selectedOptionKeys,

            Boolean booleanAnswer,

            @Size(max = 4000, message = "textAnswer must be at most 4000 characters")
            String textAnswer,

            @Size(max = 100, message = "orderingAnswer must be at most 100 items")
            List<@Min(value = 1, message = "ordering item must be >= 1") Integer> orderingAnswer,

            @Size(max = 10000, message = "matchingAnswer must be at most 10000 characters")
            String matchingAnswer
    ) {
    }
}
