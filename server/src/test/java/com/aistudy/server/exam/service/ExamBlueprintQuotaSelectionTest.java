package com.aistudy.server.exam.service;

import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.exam.dto.ExamBlueprintDto.BlueprintRules;
import com.aistudy.server.question.entity.Question;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit F-24: the blueprint distributions used to be a filter, after which the
 * paper was {@code subList(0, total)} of the id-ordered pool, so a blueprint
 * could report success while describing a paper it did not generate. These
 * tests pin the selection to the quotas without touching the database.
 */
class ExamBlueprintQuotaSelectionTest {

    private static final String SINGLE = "SINGLE_CHOICE";
    private static final String MULTIPLE = "MULTIPLE_CHOICE";

    @Test
    void honoursTypeAndDifficultyQuotasSimultaneously() {
        List<Question> pool = new ArrayList<>();
        pool.addAll(questions(8, SINGLE, "EASY", 1));
        pool.addAll(questions(8, SINGLE, "HARD", 101));
        pool.addAll(questions(8, MULTIPLE, "EASY", 201));
        pool.addAll(questions(8, MULTIPLE, "HARD", 301));

        List<Question> selected = ExamBlueprintService.selectByQuotas(pool, rules(
                Map.of(SINGLE, 5, MULTIPLE, 5),
                Map.of("EASY", 6, "HARD", 4),
                10), 10);

        assertEquals(10, selected.size());
        assertEquals(5, countByType(selected, SINGLE));
        assertEquals(5, countByType(selected, MULTIPLE));
        assertEquals(6, countByDifficulty(selected, "EASY"));
        assertEquals(4, countByDifficulty(selected, "HARD"));
    }

    /**
     * The paper asks for 9 MULTIPLE and 1 SINGLE, 9 EASY and 1 HARD, and only
     * one question per remaining cell is scarce. Taking the pool in id order —
     * what the old subList did — consumes all 12 MULTIPLE/EASY questions and
     * leaves the SINGLE and HARD quotas unmet; filling scarcest-first keeps
     * their budget for the cells that are the only source of those marginals.
     */
    @Test
    void scarceCellsClaimTheirBudgetBeforeRichCellsSpendIt() {
        List<Question> pool = new ArrayList<>();
        pool.addAll(questions(12, MULTIPLE, "EASY", 1));
        pool.addAll(questions(1, SINGLE, "EASY", 101));
        pool.addAll(questions(1, MULTIPLE, "HARD", 201));

        List<Question> selected = ExamBlueprintService.selectByQuotas(pool, rules(
                Map.of(SINGLE, 1, MULTIPLE, 9),
                Map.of("EASY", 9, "HARD", 1),
                10), 10);

        assertEquals(10, selected.size());
        assertEquals(1, countByType(selected, SINGLE));
        assertEquals(9, countByType(selected, MULTIPLE));
        assertEquals(9, countByDifficulty(selected, "EASY"));
        assertEquals(1, countByDifficulty(selected, "HARD"));
    }

    /**
     * The silent shrink is the bug: nine candidates for a ten-question paper
     * used to produce a nine-question exam with HTTP 200.
     */
    @Test
    void poolSmallerThanTheTotalIsRefusedNotTrimmed() {
        List<Question> pool = questions(9, SINGLE, "EASY", 1);

        ApiException error = assertThrows(ApiException.class,
                () -> ExamBlueprintService.selectByQuotas(pool, rules(null, null, 10), 10));
        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("EXAM_BLUEPRINT_UNSATISFIED", error.code());
        assertTrue(error.detail().contains("only 9 matching questions exist"), error.detail());
    }

    @Test
    void refusalNamesTheUnsatisfiedQuotas() {
        List<Question> pool = new ArrayList<>();
        pool.addAll(questions(10, SINGLE, "EASY", 1));
        pool.addAll(questions(2, MULTIPLE, "HARD", 101));

        ApiException error = assertThrows(ApiException.class, () -> ExamBlueprintService.selectByQuotas(
                pool, rules(Map.of(SINGLE, 5, MULTIPLE, 5), Map.of("EASY", 5, "HARD", 5), 10), 10));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("EXAM_BLUEPRINT_UNSATISFIED", error.code());
        assertTrue(error.detail().contains("type MULTIPLE_CHOICE short by 3"), error.detail());
        assertTrue(error.detail().contains("difficulty HARD short by 3"), error.detail());
    }

    /**
     * Quotas that cannot add up to the requested paper length are an authoring
     * mistake, so they are a 400 rather than a shortage of questions.
     */
    @Test
    void quotasNotSummingToTheTotalAreABadRequest() {
        List<Question> pool = questions(20, SINGLE, "EASY", 1);

        ResponseStatusException typeError = assertThrows(ResponseStatusException.class,
                () -> ExamBlueprintService.selectByQuotas(
                        pool, rules(Map.of(SINGLE, 7), null, 10), 10));
        assertEquals(HttpStatus.BAD_REQUEST, typeError.getStatusCode());
        assertTrue(typeError.getReason().contains("questionTypeDistribution"), typeError.getReason());

        ResponseStatusException difficultyError = assertThrows(ResponseStatusException.class,
                () -> ExamBlueprintService.selectByQuotas(
                        pool, rules(null, Map.of("EASY", 4), 10), 10));
        assertEquals(HttpStatus.BAD_REQUEST, difficultyError.getStatusCode());
        assertTrue(difficultyError.getReason().contains("difficultyDistribution"), difficultyError.getReason());
    }

    @Test
    void unconstrainedBlueprintTakesDeterministicPrefix() {
        List<Question> pool = questions(12, SINGLE, "EASY", 1);

        List<Question> selected = ExamBlueprintService.selectByQuotas(pool, rules(null, null, 5), 5);

        assertEquals(5, selected.size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(selected));
    }

    @Test
    void selectionIsOrderedByIdRatherThanByBucket() {
        List<Question> pool = new ArrayList<>();
        pool.addAll(questions(4, MULTIPLE, "HARD", 501));
        pool.addAll(questions(4, SINGLE, "EASY", 11));

        List<Question> selected = ExamBlueprintService.selectByQuotas(pool, rules(
                Map.of(SINGLE, 2, MULTIPLE, 2), Map.of("EASY", 2, "HARD", 2), 4), 4);

        assertEquals(List.of(11L, 12L, 501L, 502L), ids(selected));
    }

    /** A null difficulty is the same row as MEDIUM for both quotas and scoring. */
    @Test
    void nullDifficultyCountsAsMedium() {
        List<Question> pool = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            pool.add(question(id, SINGLE, null));
        }
        pool.addAll(questions(4, SINGLE, "EASY", 101));

        List<Question> selected = ExamBlueprintService.selectByQuotas(pool, rules(
                Map.of(SINGLE, 5), Map.of("MEDIUM", 3, "EASY", 2), 5), 5);

        assertEquals(3, selected.stream().filter(q -> q.getDifficulty() == null).count());
        assertEquals(2, countByDifficulty(selected, "EASY"));
    }

    // ==================== helpers ====================

    private static BlueprintRules rules(Map<String, Integer> typeQuotas,
                                        Map<String, Integer> difficultyQuotas,
                                        int total) {
        return new BlueprintRules(typeQuotas, difficultyQuotas, null, null, null, null, null, total, 30);
    }

    private static List<Question> questions(int count, String type, String difficulty, long firstId) {
        List<Question> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(question(firstId + i, type, difficulty));
        }
        return list;
    }

    private static Question question(long id, String type, String difficulty) {
        Question question = new Question();
        question.setId(id);
        question.setSpaceId(1L);
        question.setQuestionType(type);
        question.setDifficulty(difficulty);
        question.setStatus("PUBLISHED");
        question.setStem("q" + id);
        return question;
    }

    private static List<Long> ids(List<Question> questions) {
        return questions.stream().map(Question::getId).toList();
    }

    private static long countByType(List<Question> questions, String type) {
        return questions.stream().filter(q -> type.equals(q.getQuestionType())).count();
    }

    private static long countByDifficulty(List<Question> questions, String difficulty) {
        return questions.stream()
                .filter(q -> difficulty.equals(q.getDifficulty() == null ? "MEDIUM" : q.getDifficulty()))
                .count();
    }
}
