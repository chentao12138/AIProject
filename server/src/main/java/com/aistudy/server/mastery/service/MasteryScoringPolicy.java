package com.aistudy.server.mastery.service;

/**
 * BUSINESS-014 — deterministic, explainable mastery policy.
 *
 * <pre>
 *   masteryScore = correctCount / gradedCount      (0..1)
 *   confidence   = min(1.0, gradedCount / 5.0)    (sample-size proxy)
 * </pre>
 *
 * <p>Evidence = graded objective answers (practice + exam) of
 * questions linked to the knowledge point. No AI; pure deterministic
 * arithmetic so any score is explainable from its evidence counts.
 * Replaceable policy — never hardcoded in services.
 */
public final class MasteryScoringPolicy {

    /** Evidence samples needed for full confidence. */
    public static final int CONFIDENCE_FULL_SAMPLES = 5;

    private MasteryScoringPolicy() {
    }

    public static double score(int correctCount, int gradedCount) {
        if (gradedCount <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) correctCount / gradedCount);
    }

    public static double confidence(int gradedCount) {
        return Math.min(1.0, gradedCount / (double) CONFIDENCE_FULL_SAMPLES);
    }
}
