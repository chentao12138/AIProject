package com.aistudy.server.question.dto;

import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionOption;

import java.util.List;

/**
 * BUSINESS-008 — SAFE question view for practice / exam clients.
 *
 * <p>Deliberately contains NO answer data: no correctOptionKey /
 * correctOptionKeys / correctBoolean / referenceAnswer / explanation.
 * This is the only question shape served by practice (009/010) and
 * exam (012/013) question views before submit (api-guidelines.md §9,
 * runbook §6.4). The correctness-revealing {@link
 * QuestionAuthoringResponse} is served by question-bank (owner)
 * endpoints only.
 */
public record QuestionPracticeResponse(
        Long questionId,
        String questionType,
        String stem,
        List<QuestionOptionPublicView> options
) {

    public static QuestionPracticeResponse from(Question question, List<QuestionOption> options) {
        return new QuestionPracticeResponse(
                question.getId(),
                question.getQuestionType(),
                question.getStem(),
                options == null ? List.of() : options.stream()
                        .map(o -> new QuestionOptionPublicView(
                                o.getOptionKey(), o.getContent(), o.getSortOrder()))
                        .toList()
        );
    }

    /** Option without any correctness flag. */
    public record QuestionOptionPublicView(
            String optionKey,
            String content,
            Integer sortOrder
    ) {
    }
}
