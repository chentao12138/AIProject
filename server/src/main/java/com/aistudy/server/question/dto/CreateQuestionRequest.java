package com.aistudy.server.question.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * BUSINESS-008 — request body for
 * {@code POST /api/v1/spaces/{spaceId}/questions}.
 *
 * <p>Typed flat contract; the correct answer is expressed with the
 * type-specific field (mutually exclusive, validated by the service):
 *
 * <pre>
 *   SINGLE_CHOICE    options + correctOptionKey
 *   MULTIPLE_CHOICE  options + correctOptionKeys (exact-set)
 *   TRUE_FALSE       correctBoolean (options must be absent)
 *   SHORT_ANSWER     referenceAnswer optional (options must be absent)
 * </pre>
 *
 * <p>Server-controlled fields are deliberately absent: spaceId
 * (path), status (DRAFT), originType (USER_CURATED), createdByUserId
 * (JWT sub), timestamps, publishedAt, deletedAt.
 *
 * @param questionType      SINGLE_CHOICE | MULTIPLE_CHOICE | TRUE_FALSE | SHORT_ANSWER
 * @param stem              question body
 * @param explanation       optional post-answer explanation
 * @param difficulty        free-form bounded string or {@code null}
 * @param options           choice options (objective choice types only)
 * @param correctOptionKey  SINGLE_CHOICE correct option key
 * @param correctOptionKeys MULTIPLE_CHOICE correct option keys (exact set)
 * @param correctBoolean    TRUE_FALSE correct value
 * @param referenceAnswer   SHORT_ANSWER grading reference (authoring only)
 * @param knowledgePointIds optional same-space knowledge points
 */
public record CreateQuestionRequest(
        @NotBlank(message = "questionType must not be blank")
        @Size(max = 32, message = "questionType must be at most 32 characters")
        String questionType,

        @NotBlank(message = "stem must not be blank")
        String stem,

        @Size(max = 4000, message = "explanation must be at most 4000 characters")
        String explanation,

        @Size(max = 32, message = "difficulty must be at most 32 characters")
        String difficulty,

        @Valid
        List<QuestionOptionInput> options,

        @Size(max = 16, message = "correctOptionKey must be at most 16 characters")
        String correctOptionKey,

        @Size(max = 16, message = "each correct option key must be at most 16 characters")
        List<@NotBlank(message = "correct option key must not be blank")
             @Size(max = 16, message = "correct option key must be at most 16 characters") String> correctOptionKeys,

        Boolean correctBoolean,

        @Size(max = 4000, message = "referenceAnswer must be at most 4000 characters")
        String referenceAnswer,

        @Size(max = 100, message = "correctOrder must be at most 100 items")
        List<Integer> correctOrder,

        @Size(max = 4000, message = "matches must be at most 4000 characters")
        String matches,

        List<Long> knowledgePointIds
) {

    /**
     * One choice option. {@code sortOrder} defaults to the list index
     * when null (deterministic server-side fill).
     */
    public record QuestionOptionInput(
            @NotBlank(message = "optionKey must not be blank")
            @Size(max = 16, message = "optionKey must be at most 16 characters")
            String optionKey,

            @NotBlank(message = "option content must not be blank")
            @Size(max = 500, message = "option content must be at most 500 characters")
            String content,

            Integer sortOrder
    ) {
    }
}
