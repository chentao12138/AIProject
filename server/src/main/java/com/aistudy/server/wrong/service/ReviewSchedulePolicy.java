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

    // SM-2 V1 constants
    public static final double SM2_DEFAULT_EASE_FACTOR = 2.5;
    public static final int SM2_DEFAULT_INTERVAL_DAYS = 1;
    public static final int SM2_DEFAULT_REPETITIONS = 0;
    public static final int SM2_PASS_QUALITY_THRESHOLD = 3;
    public static final int SM2_MIN_INTERVAL_DAYS = 1;

    public static final String REASON_WRONG_ANSWER = "WRONG_ANSWER";
    public static final String REASON_EXAM_DIAGNOSIS = "EXAM_DIAGNOSIS";
    public static final String REASON_LOW_MASTERY = "LOW_MASTERY";
    public static final String REASON_MANUAL = "MANUAL";
    public static final String REASON_SCHEDULED = "SCHEDULED";
    public static final String REASON_REVIEW_WRONG = "REVIEW_WRONG";
    public static final String REASON_REVIEW_CORRECT = "REVIEW_CORRECT";

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

    // ==================== SM-2-compatible scheduling ====================

    /** Result of an SM-2 calculation. */
    public record Sm2Result(double easeFactor, int intervalDays, int repetitions) {
    }

    /**
     * Initial SM-2 calculation for a first review completion.
     *
     * <pre>
     *   quality >= 3  → interval=1, repetitions=1
     *   quality <  3  → interval=1, repetitions=0  (reset)
     * </pre>
     */
    public static Sm2Result sm2Initial(int quality) {
        if (quality >= SM2_PASS_QUALITY_THRESHOLD) {
            return new Sm2Result(SM2_DEFAULT_EASE_FACTOR, 1, 1);
        }
        return new Sm2Result(SM2_DEFAULT_EASE_FACTOR, 1, 0);
    }

    /**
     * Advances an existing SM-2 state by one completion.
     *
     * <pre>
     *   quality >= 3  → repetitions += 1
     *       rep 1: interval=1
     *       rep 2: interval=6
     *       rep n: interval = round(previousInterval * easeFactor)
     *       easeFactor = easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
     *   quality <  3  → repetitions=0, interval=1 (reset)
     * </pre>
     *
     * @param previousInterval last scheduled interval in days (from ReviewState)
     */
    public static Sm2Result sm2Advance(int quality, int repetitions, double easeFactor,
                                       int previousInterval) {
        if (quality < SM2_PASS_QUALITY_THRESHOLD) {
            return new Sm2Result(clampEase(easeFactor, quality), 1, 0);
        }
        int newRepetitions = repetitions + 1;
        int interval;
        if (newRepetitions == 1) {
            interval = 1;
        } else if (newRepetitions == 2) {
            interval = 6;
        } else {
            double prev = previousInterval > 0 ? previousInterval : 1.0;
            interval = Math.max(SM2_MIN_INTERVAL_DAYS,
                    (int) Math.round(prev * easeFactor));
        }
        double newEase = easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
        return new Sm2Result(clampEase(newEase, quality), interval, newRepetitions);
    }

    /** Backward-compatible overload; prefers {@link #sm2Advance(int,int,double,int)}. */
    public static Sm2Result sm2Advance(int quality, int repetitions, double easeFactor) {
        return sm2Advance(quality, repetitions, easeFactor, repetitions <= 1 ? 1 : (repetitions == 2 ? 6 : repetitions));
    }

    private static double clampEase(double ease, int quality) {
        if (ease < 1.3) {
            return 1.3;
        }
        return Math.round(ease * 100.0) / 100.0;
    }
}
