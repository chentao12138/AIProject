package com.aistudy.server.wrong.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-011 — unit tests for {@link ReviewSchedulePolicy}.
 *
 * <p>Fixes the deterministic V1 schedule: wrong +1d, first correct
 * +3d, consecutive correct +7d; priority from wrong count; status
 * transitions ACTIVE/IMPROVING/MASTERED.
 */
class ReviewSchedulePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 12, 0, 0);

    @Test
    void wrongDueIsNextDay() {
        assertEquals(NOW.plusDays(1).truncatedTo(ChronoUnit.MICROS),
                ReviewSchedulePolicy.dueAfterWrong(NOW));
    }

    @Test
    void correctDueDependsOnStreak() {
        assertEquals(NOW.plusDays(3).truncatedTo(ChronoUnit.MICROS),
                ReviewSchedulePolicy.dueAfterCorrect(NOW, false));
        assertEquals(NOW.plusDays(7).truncatedTo(ChronoUnit.MICROS),
                ReviewSchedulePolicy.dueAfterCorrect(NOW, true));
    }

    @Test
    void priorityFromWrongCount() {
        assertEquals("LOW", ReviewSchedulePolicy.priority(0));
        assertEquals("LOW", ReviewSchedulePolicy.priority(1));
        assertEquals("MEDIUM", ReviewSchedulePolicy.priority(2));
        assertEquals("HIGH", ReviewSchedulePolicy.priority(3));
        assertEquals("HIGH", ReviewSchedulePolicy.priority(9));
    }

    @Test
    void wrongReviewKeepsActive() {
        assertEquals("ACTIVE", ReviewSchedulePolicy.statusAfterReview(
                "WRONG", List.of("CORRECT", "CORRECT")));
    }

    @Test
    void firstCorrectReviewIsImproving() {
        assertEquals("IMPROVING", ReviewSchedulePolicy.statusAfterReview(
                "CORRECT", List.of("CORRECT", "WRONG")));
        assertEquals("IMPROVING", ReviewSchedulePolicy.statusAfterReview(
                "CORRECT", List.of("CORRECT")));
    }

    @Test
    void twoConsecutiveCorrectReviewsMaster() {
        assertEquals("MASTERED", ReviewSchedulePolicy.statusAfterReview(
                "CORRECT", List.of("CORRECT", "CORRECT")));
        // broken streak → not mastered
        assertFalse("MASTERED".equals(ReviewSchedulePolicy.statusAfterReview(
                "CORRECT", List.of("CORRECT", "WRONG", "CORRECT"))));
        assertTrue("IMPROVING".equals(ReviewSchedulePolicy.statusAfterReview(
                "CORRECT", List.of("CORRECT", "WRONG", "CORRECT"))));
    }
}
