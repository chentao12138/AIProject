package com.aistudy.server.ingestion.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.io.File;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-28 / F-10 tests for the wiring around the OCR abstraction: the outcome
 * vocabulary's own invariants, engine selection by policy string, and the
 * health detail never escalating to DOWN.
 *
 * <p>The health rule matters operationally: if an unavailable OCR sidecar made
 * {@code /actuator/health} report DOWN, the orchestrator would restart the
 * backend for a dependency that only image ingestion needs.
 */
class OcrWiringTest {

    @Test
    void outcomeVocabularyIsBoundedAndClassifiesFailuresDistinctly() {
        assertEquals(10, OcrOutcome.values().length,
                "the outcome vocabulary is a contract; growing it needs the pipeline mapping updated too");
        assertTrue(OcrOutcome.UNAVAILABLE.isFailure());
        assertTrue(OcrOutcome.TIMEOUT.isFailure());
        assertTrue(OcrOutcome.OUTPUT_TOO_LARGE.isFailure());
        assertFalse(OcrOutcome.NO_TEXT.isFailure(), "no text is an answer about the asset");
        assertFalse(OcrOutcome.CONFIDENCE_UNKNOWN.isFailure(), "unknown confidence needs review, not a failure");
        assertFalse(OcrOutcome.OK.needsReview());
        assertFalse(OcrOutcome.SKIPPED_NO_ENGINE.needsReview());
        assertTrue(OcrOutcome.LOW_CONFIDENCE.needsReview());
        assertEquals("OCR_TIMEOUT", OcrOutcome.TIMEOUT.stableCode());
        assertEquals(OcrOutcome.OUTPUT_UNPARSEABLE, OcrOutcome.fromLabel("GARBAGE"),
                "an unrecognised label must never read as OK");
        assertEquals(OcrOutcome.OUTPUT_UNPARSEABLE, OcrOutcome.fromLabel(null));
    }

    @Test
    void legacyFourArgResultDerivesItsOutcome() {
        // Test doubles and older callers build the 4-argument form; they must not
        // silently be treated as a confident success.
        assertEquals(OcrOutcome.OK, new OcrEngine.OcrResult("text", 0.9, "e", null).outcome());
        assertEquals(OcrOutcome.CONFIDENCE_UNKNOWN,
                new OcrEngine.OcrResult("text", Double.NaN, "e", null).outcome());
        assertEquals(OcrOutcome.NO_TEXT, new OcrEngine.OcrResult("  ", 0.9, "e", null).outcome());
        assertEquals(OcrOutcome.NO_TEXT, new OcrEngine.OcrResult(null, 0.9, "e", null).outcome());
        assertEquals("", new OcrEngine.OcrResult(null, 0.9, "e", null).text());
    }

    @Test
    void failurePolicyParsesConservatively() {
        assertEquals(OcrFailurePolicy.FAIL, OcrEngineConfig.parseFailurePolicy("fail"));
        assertEquals(OcrFailurePolicy.FAIL, OcrEngineConfig.parseFailurePolicy(" FAIL "));
        assertEquals(OcrFailurePolicy.REVIEW, OcrEngineConfig.parseFailurePolicy("review"));
        assertEquals(OcrFailurePolicy.REVIEW, OcrEngineConfig.parseFailurePolicy(null),
                "an unreadable policy must not turn every image into a hard failure");
        assertEquals(OcrFailurePolicy.REVIEW, OcrEngineConfig.parseFailurePolicy("ignore-everything"));
    }

    @Test
    void disabledEngineIsNeverTreatedAsUsable() {
        DisabledOcrEngine engine = new DisabledOcrEngine();
        assertFalse(engine.available());
        assertEquals("none", engine.name());
        OcrException error = assertThrows(OcrException.class, () -> engine.ocr(new File("x.png")));
        assertEquals(OcrOutcome.UNAVAILABLE, error.outcome());
    }

    @Test
    void healthDetailStaysUpWhenOcrIsNotDeployed() {
        OcrHealthIndicator indicator = new OcrHealthIndicator(new DisabledOcrEngine(), false, false,
                Duration.ofSeconds(1));
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void healthDetailDegradesInsteadOfGoingDown() {
        OcrEngine engine = mock(OcrEngine.class);
        when(engine.available()).thenReturn(false);
        when(engine.name()).thenReturn("PaddleOCR");

        OcrHealthIndicator indicator = new OcrHealthIndicator(engine, true, false, Duration.ofSeconds(1));
        var health = indicator.health();
        assertEquals("DEGRADED", health.getStatus().getCode());
        assertNotDown(health.getStatus());
        assertEquals("OCR engine is configured but not reachable or not installed",
                health.getDetails().get("reason"));
        assertNotNull(health.getDetails().get("action"));
    }

    @Test
    void strictHealthGatingIsAnExplicitOperatorChoice() {
        OcrEngine engine = mock(OcrEngine.class);
        when(engine.available()).thenReturn(false);

        OcrHealthIndicator strict = new OcrHealthIndicator(engine, true, true, Duration.ofSeconds(1));
        assertEquals(Status.DOWN, strict.health().getStatus(),
                "only when the operator opts in may OCR take the pod out of rotation");
    }

    @Test
    void healthDetailIsUpWhenTheEngineReportsUsable() {
        OcrEngine engine = mock(OcrEngine.class);
        when(engine.available()).thenReturn(true);
        when(engine.name()).thenReturn("PaddleOCR");

        OcrHealthIndicator indicator = new OcrHealthIndicator(engine, true, false, Duration.ofSeconds(1));
        assertEquals(Status.UP, indicator.health().getStatus());
        assertEquals("local-process", indicator.health().getDetails().get("endpoint"));
    }

    @Test
    void probeFailureIsCaughtNotThrown() {
        OcrEngine exploding = new OcrEngine() {
            @Override
            public OcrResult ocr(File imageFile) {
                throw new OcrException(OcrOutcome.PROCESS_FAILED, "boom", null, null);
            }

            @Override
            public boolean available() {
                throw new IllegalStateException("probe blew up");
            }

            @Override
            public String name() {
                return "broken";
            }
        };
        OcrHealthIndicator indicator = new OcrHealthIndicator(exploding, true, false, Duration.ofSeconds(1));
        assertEquals("DEGRADED", indicator.health().getStatus().getCode(),
                "a health check must never be the thing that breaks the request");
    }

    private static void assertNotDown(Status status) {
        assertFalse(Status.DOWN.equals(status), "custom statuses must not escalate the composite");
    }
}
