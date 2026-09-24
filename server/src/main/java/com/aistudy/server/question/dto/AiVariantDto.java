package com.aistudy.server.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * §5.8 — DTOs for AI-derived variant question generation.
 *
 * <p>Variant questions are temporary AI-generated paraphrases of an
 * existing question, keyed to the same knowledge point(s). They are
 * returned as transient objects; if the user chooses to save them they
 * are persisted as new Question rows with originType=AI_DERIVED and
 * status=DRAFT.
 */
public final class AiVariantDto {

    private AiVariantDto() {
    }

    /** Request body for POST /api/v1/.../questions/{questionId}/variant. */
    public record VariantRequest(
            @Size(max = 4000, message = "instructions must be at most 4000 characters")
            String instructions,

            @Size(max = 20, message = "at most 20 knowledge point ids allowed")
            List<Long> knowledgePointIds
    ) {
    }

    /** One variant produced by the AI (not yet persisted). */
    public record VariantResponse(
            Long id,
            String questionType,
            String stem,
            List<OptionView> options,
            String answerDataJson,
            String explanation
    ) {
    }

    /** Saved variant response (persisted as a new Question). */
    public record SavedVariantResponse(
            Long questionId,
            String status,
            String originType
    ) {
    }

    /** Option without any correctness flag (same shape as practice/exam views). */
    public record OptionView(String optionKey, String content, Integer sortOrder) {
    }
}
