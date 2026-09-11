package com.aistudy.server.mastery.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-014 — pure unit tests for {@link MasteryScoringPolicy}.
 *
 * <p>Deterministic, explainable arithmetic: score = correct/graded
 * (0..1), confidence = min(1, graded/5). No mocking, no Spring.
 */
class MasteryScoringPolicyTest {

    /** (1) no evidence → score 0, confidence 0. */
    @Test
    void noEvidenceIsZeroScoreZeroConfidence() {
        assertEquals(0.0, MasteryScoringPolicy.score(0, 0), 1e-9);
        assertEquals(0.0, MasteryScoringPolicy.confidence(0), 1e-9);
    }

    /** (2) 1/1 → 1.0. */
    @Test
    void perfectSingleSampleIsOne() {
        assertEquals(1.0, MasteryScoringPolicy.score(1, 1), 1e-9);
    }

    /** (3) 1/2 → 0.5. */
    @Test
    void oneOfTwoIsHalf() {
        assertEquals(0.5, MasteryScoringPolicy.score(1, 2), 1e-9);
    }

    /** (4) 2/4 → 0.5. */
    @Test
    void twoOfFourIsHalf() {
        assertEquals(0.5, MasteryScoringPolicy.score(2, 4), 1e-9);
    }

    /** (5) confidence reaches 1.0 exactly at 5 samples. */
    @Test
    void confidenceFullAtFiveSamples() {
        assertEquals(0.2, MasteryScoringPolicy.confidence(1), 1e-9);
        assertEquals(0.4, MasteryScoringPolicy.confidence(2), 1e-9);
        assertEquals(0.6, MasteryScoringPolicy.confidence(3), 1e-9);
        assertEquals(0.8, MasteryScoringPolicy.confidence(4), 1e-9);
        assertEquals(1.0, MasteryScoringPolicy.confidence(5), 1e-9);
    }

    /** (6) confidence clamps at 1.0 beyond 5 samples. */
    @Test
    void confidenceClampsAboveFive() {
        assertEquals(1.0, MasteryScoringPolicy.confidence(6), 1e-9);
        assertEquals(1.0, MasteryScoringPolicy.confidence(100), 1e-9);
    }

    /** (7) bounds: score never exceeds 1.0, never negative. */
    @Test
    void scoreStaysBounded() {
        assertTrue(MasteryScoringPolicy.score(0, 3) >= 0.0);
        assertTrue(MasteryScoringPolicy.score(3, 3) <= 1.0);
        assertTrue(MasteryScoringPolicy.score(10, 5) <= 1.0,
                "correct can never exceed graded in practice, but policy still clamps");
        assertEquals(1.0, MasteryScoringPolicy.score(10, 5), 1e-9);
    }
}
