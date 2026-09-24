package com.aistudy.server.question.eval;

import com.aistudy.server.question.eval.QuestionAnswerEvaluator.AnswerPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-010 — unit tests for the SHARED {@link QuestionAnswerEvaluator}.
 *
 * <p>Practice and Exam (013) MUST share this exact grading semantic:
 * SINGLE exact key, MULTIPLE exact-set, TRUE_FALSE boolean equality,
 * SHORT_ANSWER ungraded. These tests fix the semantic so neither
 * module can drift.
 */
class QuestionAnswerEvaluatorTest {

    private static AnswerDataCodec.AnswerData single(String key) {
        return new AnswerDataCodec.AnswerData(key, null, null, null, null, null);
    }

    private static AnswerDataCodec.AnswerData multiple(String... keys) {
        return new AnswerDataCodec.AnswerData(null, List.of(keys), null, null, null, null);
    }

    private static AnswerDataCodec.AnswerData bool(Boolean value) {
        return new AnswerDataCodec.AnswerData(null, null, value, null, null, null);
    }

    @Test
    void singleChoiceCorrectAndWrong() {
        var correct = QuestionAnswerEvaluator.grade("SINGLE_CHOICE", single("B"),
                AnswerPayload.of(List.of("B"), null, null));
        assertTrue(correct.isCorrect());
        assertEquals(1, correct.score());
        assertEquals("B", correct.correctAnswerSummary());

        var wrong = QuestionAnswerEvaluator.grade("SINGLE_CHOICE", single("B"),
                AnswerPayload.of(List.of("A"), null, null));
        assertFalse(wrong.isCorrect());
        assertEquals(0, wrong.score());
    }

    @Test
    void singleChoiceRequiresExactlyOneKey() {
        assertThrows(IllegalArgumentException.class, () ->
                QuestionAnswerEvaluator.grade("SINGLE_CHOICE", single("B"),
                        AnswerPayload.of(List.of(), null, null)));
        assertThrows(IllegalArgumentException.class, () ->
                QuestionAnswerEvaluator.grade("SINGLE_CHOICE", single("B"),
                        AnswerPayload.of(List.of("A", "B"), null, null)));
    }

    @Test
    void multipleChoiceIsExactSetMatch() {
        var answer = AnswerPayload.of(List.of("A", "C"), null, null);
        assertTrue(QuestionAnswerEvaluator.grade("MULTIPLE_CHOICE", multiple("A", "C"), answer)
                .isCorrect());
        assertTrue(QuestionAnswerEvaluator.grade("MULTIPLE_CHOICE", multiple("C", "A"),
                AnswerPayload.of(List.of("C", "A"), null, null))
                .isCorrect());
        assertFalse(QuestionAnswerEvaluator.grade("MULTIPLE_CHOICE", multiple("A", "C"),
                AnswerPayload.of(List.of("A"), null, null)).isCorrect());
        assertFalse(QuestionAnswerEvaluator.grade("MULTIPLE_CHOICE", multiple("A", "C"),
                AnswerPayload.of(List.of("A", "B", "C"), null, null))
                .isCorrect());
        assertThrows(IllegalArgumentException.class, () ->
                QuestionAnswerEvaluator.grade("MULTIPLE_CHOICE", multiple("A", "C"),
                        AnswerPayload.of(List.of(), null, null)));
    }

    @Test
    void trueFalseMatchesBooleanExactly() {
        assertTrue(QuestionAnswerEvaluator.grade("TRUE_FALSE", bool(true),
                AnswerPayload.of(null, true, null)).isCorrect());
        assertFalse(QuestionAnswerEvaluator.grade("TRUE_FALSE", bool(true),
                AnswerPayload.of(null, false, null)).isCorrect());
        assertThrows(IllegalArgumentException.class, () ->
                QuestionAnswerEvaluator.grade("TRUE_FALSE", bool(true),
                        AnswerPayload.of(null, null, null)));
    }

    @Test
    void shortAnswerIsUngraded() {
        var result = QuestionAnswerEvaluator.grade("SHORT_ANSWER", null,
                AnswerPayload.of(null, null, "ACID"));
        assertNull(result.isCorrect());
        assertNull(result.score());
        assertNull(result.correctAnswerSummary());
    }

    @Test
    void unknownTypeRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                QuestionAnswerEvaluator.grade("ESSAY", single("A"),
                        AnswerPayload.of(List.of("A"), null, null)));
    }
}
