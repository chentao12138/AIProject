package com.aistudy.server.wrong.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * BUSINESS-011 — deterministic V1 review scheduling policy.
 *
 * <p>Encapsulates ALL spacing/priority decisions so services never
 * hardcode intervals (runbook §7.3). Explicitly replaceable; not SM-2.
 *
 * <pre>
 *   wrong answer (first or again)  due = now + 1 day
 *   review correct, previous wrong  due = now + 3 days
 *   review correct, consecutive     due = now + 7 days
 * </pre>
 *
 * <p>Priority by wrong count: {@code >=3 → HIGH}, {@code 2 → MEDIUM},
 * {@code <=1 → LOW}. All computations truncate to MICROS so values
 * round-trip MySQL DATETIME(6) exactly.
 */
public final class ReviewSchedulePolicy {

    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_MEDIUM = "MEDIUM";
    public static final String PRIORITY_LOW = "LOW";

    public static final String RESULT_CORRECT = "CORRECT";
    public static final String RESULT_WRONG = "WRONG";

    private ReviewSchedulePolicy() {
    }

    /** Due time after a WRONG answer. */
    public static LocalDateTime dueAfterWrong(LocalDateTime now) {
        return now.plusDays(1).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Due time after a CORRECT review completion.
     *
     * @param previousReviewWasCorrect whether the previous completion
     *        of the same target was also CORRECT (consecutive streak)
     */
    public static LocalDateTime dueAfterCorrect(LocalDateTime now, boolean previousReviewWasCorrect) {
        return now.plusDays(previousReviewWasCorrect ? 7 : 3).truncatedTo(ChronoUnit.MICROS);
    }

    /** Priority from wrong count. */
    public static String priority(int wrongCount) {
        if (wrongCount >= 3) {
            return PRIORITY_HIGH;
        }
        if (wrongCount == 2) {
            return PRIORITY_MEDIUM;
        }
        return PRIORITY_LOW;
    }

    /**
     * Wrong-question status after a review completion.
     *
     * <pre>
     *   WRONG    → ACTIVE
     *   CORRECT  → IMPROVING, unless the two most recent completions
     *              are BOTH correct → MASTERED
     * </pre>
     *
     * @param recentResults most recent first; may be empty
     */
    public static String statusAfterReview(String result, java.util.List<String> recentResults) {
        if (RESULT_WRONG.equals(result)) {
            return "ACTIVE";
        }
        boolean consecutive = recentResults != null
                && recentResults.size() >= 2
                && RESULT_CORRECT.equals(recentResults.get(0))
                && RESULT_CORRECT.equals(recentResults.get(1));
        return consecutive ? "MASTERED" : "IMPROVING";
    }
}
