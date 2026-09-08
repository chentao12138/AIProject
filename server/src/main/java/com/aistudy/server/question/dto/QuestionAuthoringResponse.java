package com.aistudy.server.question.dto;

import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionKnowledgePoint;
import com.aistudy.server.question.entity.QuestionOption;
import com.aistudy.server.question.eval.AnswerDataCodec;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-008 — authoring response for Question endpoints.
 *
 * <p>This is the QUESTION BANK MANAGEMENT view: it includes the
 * correct answer ({@code answer}) and per-option correctness. It is
 * served to the OWNER only (all reads are owner-scoped). Practice and
 * Exam use {@code QuestionPracticeResponse} instead, which never
 * carries answer data.
 */
public record QuestionAuthoringResponse(
        Long id,
        Long spaceId,
        String questionType,
        String stem,
        String explanation,
        String difficulty,
        String originType,
        String status,
        List<QuestionOptionResponse> options,
        QuestionAnswerView answer,
        List<Long> knowledgePointIds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime publishedAt
) {

    public static QuestionAuthoringResponse from(Question question,
                                                 List<QuestionOption> options,
                                                 List<QuestionKnowledgePoint> knowledgePoints) {
        AnswerDataCodec.AnswerData answerData = AnswerDataCodec.parse(question.getAnswerDataJson());
        return new QuestionAuthoringResponse(
                question.getId(),
                question.getSpaceId(),
                question.getQuestionType(),
                question.getStem(),
                question.getExplanation(),
                question.getDifficulty(),
                question.getOriginType(),
                question.getStatus(),
                options == null ? List.of() : options.stream()
                        .map(o -> QuestionOptionResponse.from(o, optionIsCorrect(o.getOptionKey(), answerData)))
                        .toList(),
                new QuestionAnswerView(
                        answerData.correctOptionKey(),
                        answerData.correctOptionKeys(),
                        answerData.correctBoolean(),
                        answerData.referenceAnswer()
                ),
                knowledgePoints == null ? List.of() : knowledgePoints.stream()
                        .map(QuestionKnowledgePoint::getKnowledgePointId)
                        .toList(),
                question.getCreatedAt(),
                question.getUpdatedAt(),
                question.getPublishedAt()
        );
    }

    /** True when the option key is part of the question's answer data. */
    private static boolean optionIsCorrect(String optionKey, AnswerDataCodec.AnswerData answerData) {
        if (answerData.correctOptionKey() != null) {
            return answerData.correctOptionKey().equals(optionKey);
        }
        if (answerData.correctOptionKeys() != null) {
            return answerData.correctOptionKeys().contains(optionKey);
        }
        return false;
    }

    /** One option of the authoring view, with its correctness flag. */
    public record QuestionOptionResponse(
            Long id,
            String optionKey,
            String content,
            Integer sortOrder,
            boolean isCorrect
    ) {
        static QuestionOptionResponse from(QuestionOption option, boolean isCorrect) {
            return new QuestionOptionResponse(
                    option.getId(),
                    option.getOptionKey(),
                    option.getContent(),
                    option.getSortOrder(),
                    isCorrect
            );
        }
    }

    /**
     * Authoring-only answer view. {@code isCorrect} on options is
     * resolved by the service (option key membership in the answer
     * data), never stored on the option row.
     */
    public record QuestionAnswerView(
            String correctOptionKey,
            List<String> correctOptionKeys,
            Boolean correctBoolean,
            String referenceAnswer
    ) {
    }
}
