package com.aistudy.server.mastery.service;

/**
 * BUSINESS-014 / Final Backend — deterministic mastery policy.
 *
 * <pre>
 *   evidence = practice + exam + review (CORRECT/WRONG graded records)
 *   masteryScore = correctCount / gradedCount      (0..1)
 *   confidence   = min(1.0, gradedCount / fullSamples)
 * </pre>
 *
 * Review CORRECT/WRONG participates in BOTH score and confidence so a
 * completed spaced-repetition evidence changes mastery explainably.
 * No AI; pure deterministic arithmetic. algorithmVersion records the
 * active calibration version used for a recompute.
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
        return confidence(gradedCount, CONFIDENCE_FULL_SAMPLES);
    }

    public static double confidence(int gradedCount, int confidenceFullSamples) {
        if (confidenceFullSamples <= 0) {
            return Math.min(1.0, gradedCount / (double) CONFIDENCE_FULL_SAMPLES);
        }
        return Math.min(1.0, gradedCount / (double) confidenceFullSamples);
    }
}
