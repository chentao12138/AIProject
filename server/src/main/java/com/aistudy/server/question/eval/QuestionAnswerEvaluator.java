package com.aistudy.server.question.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BUSINESS-010 — shared objective answer evaluator.
 *
 * <p>The SINGLE grading semantic for Practice (010) and Exam (013):
 * correctness is always decided against the FROZEN question snapshot
 * (never the live question), and only for objective types:
 *
 * <pre>
 *   SINGLE_CHOICE    selected == exactly one key == correctOptionKey
 *   MULTIPLE_CHOICE  selected set equals correctOptionKeys set (exact-set)
 *   TRUE_FALSE       booleanAnswer == correctBoolean
 *   FILL_BLANK       comma-delimited textAnswer == referenceAnswer (exact, case-sensitive)
 *   ORDERING         comma-delimited orderingAnswer == correctOrder (exact)
 *   MATCHING         matchingAnswer JSON object matches matchesJson (exact-set per pair)
 *   SHORT_ANSWER     ungraded: isCorrect=null, score=null
 * </pre>
 *
 * <p>Answers arriving with wrong shape for the type (e.g. textAnswer
 * on SINGLE_CHOICE) are treated as INVALID shape → 400 by the
 * caller's validation, not graded here. This class never sees raw
 * client maps; it receives the typed {@link AnswerPayload}.
 */
public final class QuestionAnswerEvaluator {

    public static final int SCORE_CORRECT = 1;
    public static final int SCORE_WRONG = 0;

    private QuestionAnswerEvaluator() {
    }

    /** Typed answer payload from the client (mutually exclusive fields). */
    public record AnswerPayload(List<String> selectedOptionKeys,
                                Boolean booleanAnswer,
                                String textAnswer,
                                List<Integer> orderingAnswer,
                                String matchingAnswer) {

        public static AnswerPayload of(List<String> selectedOptionKeys,
                                        Boolean booleanAnswer,
                                        String textAnswer) {
            return new AnswerPayload(selectedOptionKeys, booleanAnswer, textAnswer, null, null);
        }

        public static AnswerPayload of(List<String> selectedOptionKeys,
                                        Boolean booleanAnswer,
                                        String textAnswer,
                                        List<Integer> orderingAnswer,
                                        String matchingAnswer) {
            return new AnswerPayload(selectedOptionKeys, booleanAnswer, textAnswer,
                    orderingAnswer, matchingAnswer);
        }
    }

    /** Grading outcome. Short answers: {@code isCorrect=null}, {@code score=null}. */
    public record GradeResult(Boolean isCorrect, Integer score, String correctAnswerSummary) {
        public static GradeResult graded(boolean correct, String correctAnswerSummary) {
            return new GradeResult(correct, correct ? SCORE_CORRECT : SCORE_WRONG, correctAnswerSummary);
        }

        public static GradeResult ungraded() {
            return new GradeResult(null, null, null);
        }
    }

    /**
     * Grades one answer against the snapshot's answer data.
     *
     * @param questionType type from the SNAPSHOT
     * @param answerData   answer data parsed from the SNAPSHOT
     * @param payload      typed client answer
     * @return grade result; never null
     */
    public static GradeResult grade(String questionType,
                                    AnswerDataCodec.AnswerData answerData,
                                    AnswerPayload payload) {
        return switch (questionType) {
            case "SINGLE_CHOICE" -> gradeSingle(answerData, payload);
            case "MULTIPLE_CHOICE" -> gradeMultiple(answerData, payload);
            case "TRUE_FALSE" -> gradeTrueFalse(answerData, payload);
            case "FILL_BLANK" -> gradeFillBlank(answerData, payload);
            case "ORDERING" -> gradeOrdering(answerData, payload);
            case "MATCHING" -> gradeMatching(answerData, payload);
            case "SHORT_ANSWER" -> GradeResult.ungraded();
            default -> throw new IllegalArgumentException("unsupported questionType: " + questionType);
        };
    }

    private static GradeResult gradeSingle(AnswerDataCodec.AnswerData answerData,
                                           AnswerPayload payload) {
        if (payload.selectedOptionKeys() == null || payload.selectedOptionKeys().size() != 1) {
            throw new IllegalArgumentException(
                    "SINGLE_CHOICE requires exactly one selectedOptionKeys entry");
        }
        String selected = payload.selectedOptionKeys().get(0);
        boolean correct = selected.equals(answerData.correctOptionKey());
        return GradeResult.graded(correct, answerData.correctOptionKey());
    }

    private static GradeResult gradeMultiple(AnswerDataCodec.AnswerData answerData,
                                             AnswerPayload payload) {
        if (payload.selectedOptionKeys() == null || payload.selectedOptionKeys().isEmpty()) {
            throw new IllegalArgumentException(
                    "MULTIPLE_CHOICE requires at least one selectedOptionKeys entry");
        }
        Set<String> selected = new HashSet<>(payload.selectedOptionKeys());
        Set<String> correct = new HashSet<>(answerData.correctOptionKeys());
        boolean correctResult = selected.equals(correct);
        return GradeResult.graded(correctResult, String.join(",", correct));
    }

    private static GradeResult gradeTrueFalse(AnswerDataCodec.AnswerData answerData,
                                               AnswerPayload payload) {
        if (payload.booleanAnswer() == null) {
            throw new IllegalArgumentException("TRUE_FALSE requires booleanAnswer");
        }
        boolean correct = payload.booleanAnswer().equals(answerData.correctBoolean());
        return GradeResult.graded(correct, String.valueOf(answerData.correctBoolean()));
    }

    private static GradeResult gradeFillBlank(AnswerDataCodec.AnswerData answerData,
                                               AnswerPayload payload) {
        if (payload.textAnswer() == null || payload.textAnswer().isBlank()) {
            throw new IllegalArgumentException("FILL_BLANK requires textAnswer");
        }
        String reference = answerData.referenceAnswer();
        if (reference == null) {
            throw new IllegalArgumentException("FILL_BLANK has no reference answer stored");
        }
        boolean correct = payload.textAnswer().trim().equals(reference.trim());
        return GradeResult.graded(correct, reference);
    }

    private static GradeResult gradeOrdering(AnswerDataCodec.AnswerData answerData,
                                              AnswerPayload payload) {
        if (payload.orderingAnswer() == null || payload.orderingAnswer().isEmpty()) {
            throw new IllegalArgumentException("ORDERING requires orderingAnswer");
        }
        List<Integer> correct = answerData.correctOrder();
        if (correct == null || correct.isEmpty()) {
            throw new IllegalArgumentException("ORDERING has no correct order stored");
        }
        boolean correctResult = payload.orderingAnswer().equals(correct);
        return GradeResult.graded(correctResult, correct.toString());
    }

    private static GradeResult gradeMatching(AnswerDataCodec.AnswerData answerData,
                                              AnswerPayload payload) {
        if (payload.matchingAnswer() == null || payload.matchingAnswer().isBlank()) {
            throw new IllegalArgumentException("MATCHING requires matchingAnswer");
        }
        String matchesJson = answerData.matchesJson();
        if (matchesJson == null) {
            throw new IllegalArgumentException("MATCHING has no matches stored");
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<java.util.Map<String, String>> correctPairs = mapper.readValue(
                    matchesJson, mapper.getTypeFactory().constructCollectionType(
                            java.util.List.class, java.util.LinkedHashMap.class));
            java.util.List<java.util.Map<String, String>> userPairs = mapper.readValue(
                    payload.matchingAnswer(), mapper.getTypeFactory().constructCollectionType(
                            java.util.List.class, java.util.LinkedHashMap.class));
            if (correctPairs.size() != userPairs.size()) {
                return GradeResult.graded(false, matchesJson);
            }
            java.util.Set<java.util.Map<String, String>> correctSet =
                    new java.util.LinkedHashSet<>(correctPairs);
            java.util.Set<java.util.Map<String, String>> userSet =
                    new java.util.LinkedHashSet<>(userPairs);
            boolean correctResult = correctSet.equals(userSet);
            return GradeResult.graded(correctResult, matchesJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("MATCHING answer is not valid JSON: " + e.getMessage(), e);
        }
    }
}
