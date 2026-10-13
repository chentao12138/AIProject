package com.aistudy.server.ingestion.ocr;

import java.io.File;

/**
 * OCR abstraction used by the image and PDF extraction services.
 *
 * <p>Implementations are selected by configuration
 * ({@code aistudy.ocr.engine} = {@code none | process | http}); exactly one
 * {@link OcrEngine} bean is always present so the extraction services can be
 * constructed without OCR being deployed.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>{@link #ocr(File)} returns a result or throws {@link OcrException};
 *       it never returns {@code null} for a failed run, because a null used to
 *       be silently read as "no text".</li>
 *   <li>Every returned result carries an {@link OcrOutcome}; callers branch on
 *       the outcome, not on the text being blank.</li>
 *   <li>An engine that is not configured reports {@link #available()} false and
 *       must not be invoked; callers record {@link OcrOutcome#SKIPPED_NO_ENGINE}
 *       instead.</li>
 *   <li>Implementations MUST bound their own execution time and output size —
 *       the caller has no fence of its own.</li>
 * </ul>
 */
public interface OcrEngine {

    /**
     * Confidence sentinel meaning "the engine gave no usable confidence".
     *
     * <p>Encoded as {@link Double#NaN} so it cannot be mistaken for a real
     * score and cannot satisfy any {@code >= threshold} comparison; callers
     * must check {@link Double#isNaN(Double)} and store SQL NULL. A parse
     * failure must never mean "100% confident".
     */
    double CONFIDENCE_UNKNOWN = Double.NaN;

    /** @return true when an OCR run is actually possible right now */
    default boolean available() {
        return true;
    }

    /** @return short engine identifier persisted with provenance metadata */
    default String name() {
        return "unknown";
    }

    /**
     * Runs OCR over one image file.
     *
     * @param imageFile existing local image file; ownership stays with the
     *                  caller, the engine MUST NOT delete it
     * @return a non-null result carrying text (possibly blank) plus an
     *         {@link OcrOutcome}; blank text with {@link OcrOutcome#NO_TEXT}
     *         means the engine really found nothing
     * @throws OcrException when no usable result could be produced
     *                      (unavailable / timeout / output too large / failure)
     */
    OcrResult ocr(File imageFile);

    /**
     * One OCR result.
     *
     * @param text       recognised text, never {@code null} (blank when the
     *                   engine found nothing); callers bound it themselves
     * @param confidence mean confidence in {@code 0.0..1.0}, or
     *                   {@link #CONFIDENCE_UNKNOWN} when the engine could not
     *                   report one
     * @param engine     engine identifier (may be null for legacy callers)
     * @param model      model identifier, nullable
     * @param outcome    the {@link OcrOutcome} describing how this result was
     *                   obtained; never null
     */
    record OcrResult(String text, double confidence, String engine, String model, OcrOutcome outcome) {

        public OcrResult {
            if (text == null) {
                text = "";
            }
            if (outcome == null) {
                outcome = deriveOutcome(text, confidence);
            }
        }

        /**
         * Back-compatible 4-argument form used by existing callers and test
         * doubles: the outcome is derived from the values instead of being
         * assumed to be {@link OcrOutcome#OK}, so an unparseable confidence
         * still ends up under review.
         */
        public OcrResult(String text, double confidence, String engine, String model) {
            this(text, confidence, engine, model, deriveOutcome(text, confidence));
        }

        private static OcrOutcome deriveOutcome(String text, double confidence) {
            if (Double.isNaN(confidence)) {
                return text == null || text.isBlank() ? OcrOutcome.NO_TEXT : OcrOutcome.CONFIDENCE_UNKNOWN;
            }
            if (text == null || text.isBlank()) {
                return OcrOutcome.NO_TEXT;
            }
            return OcrOutcome.OK;
        }

        /** @return true when this result must be flagged for human review */
        public boolean needsReview() {
            return outcome.needsReview();
        }
    }
}
