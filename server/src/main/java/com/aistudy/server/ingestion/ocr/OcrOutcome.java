package com.aistudy.server.ingestion.ocr;

/**
 * F-28 — the bounded vocabulary of OCR / extraction sub-outcomes.
 *
 * <p>Before this enum existed, every OCR problem collapsed into one boolean
 * ({@code lowConfidence}) and the pipeline then reported it as an
 * {@code OCR_LOW_CONFIDENCE} ingestion issue. A client could not tell
 * "this asset really has no text" apart from "the OCR binary is not
 * installed", "the OCR process was killed after the deadline" or "OCR
 * returned text we cannot trust". Those four cases need different operator
 * actions, so they must be different values.
 *
 * <h3>Pipeline mapping (kept deliberately narrow)</h3>
 *
 * <p>The extraction services may not invent new job states — the job worker
 * owns that vocabulary ({@code IngestionJobService.IngestionStatus}). Every
 * outcome therefore maps onto a channel the pipeline already has:
 *
 * <table border="1">
 *   <caption>outcome &rarr; pipeline channel</caption>
 *   <tr><th>outcome</th><th>job status</th><th>errorCode</th><th>ingestion_issue</th></tr>
 *   <tr><td>{@link #OK}</td>
 *       <td>terminal success ({@code NEEDS_REVIEW} / {@code PUBLISHED})</td>
 *       <td>null</td><td>none</td></tr>
 *   <tr><td>{@link #NO_TEXT}</td>
 *       <td>terminal success, flagged for review</td>
 *       <td>null</td><td>{@code OCR_LOW_CONFIDENCE} (review required)</td></tr>
 *   <tr><td>{@link #LOW_CONFIDENCE}, {@link #CONFIDENCE_UNKNOWN}</td>
 *       <td>terminal success, flagged for review</td>
 *       <td>null</td><td>{@code OCR_LOW_CONFIDENCE}</td></tr>
 *   <tr><td>{@link #SKIPPED_NO_ENGINE}</td>
 *       <td>terminal success</td>
 *       <td>null</td><td>none (operator choice: OCR not deployed)</td></tr>
 *   <tr><td>{@link #UNAVAILABLE}, {@link #TIMEOUT}, {@link #PROCESS_FAILED},
 *       {@link #OUTPUT_TOO_LARGE}, {@link #OUTPUT_UNPARSEABLE}</td>
 *       <td>{@code FAILED} (all assets) or {@code PARTIAL_FAILED} (some assets)
 *           under {@code on-failure=fail}; {@code NEEDS_REVIEW} + issue under
 *           the default {@code on-failure=review}</td>
 *       <td>see {@link #stableCode()}</td>
 *       <td>{@code OCR_LOW_CONFIDENCE} in review mode,
 *           {@code EXTRACTION_PARTIAL_FAILURE} when another asset succeeded</td></tr>
 * </table>
 *
 * <p>{@link #stableCode()} is the precise client-facing code. Until
 * {@code IngestionErrorCode} (owned by the job lane) grows the OCR values, the
 * extraction services emit the closest existing enum constant as the job
 * {@code errorCode} and prefix the safe message with this stable code, e.g.
 * {@code "OCR_TIMEOUT: ..."}, so the distinction survives to the client
 * instead of collapsing into {@code INGESTION_FAILED}.
 */
public enum OcrOutcome {

    /** OCR ran, produced text, confidence at or above the threshold. */
    OK(false, null),

    /**
     * OCR ran successfully and the engine found no readable text. This is a
     * real result about the asset, NOT an engine failure, but it still needs
     * a human because an empty page is indistinguishable from a bad scan.
     */
    NO_TEXT(true, null),

    /** OCR produced text whose confidence is below the configured threshold. */
    LOW_CONFIDENCE(true, null),

    /**
     * OCR produced text but the engine could not report a usable confidence
     * (missing or unparseable value). Never treated as "fully confident":
     * the page stores {@code extraction_confidence = NULL} and is reviewed.
     */
    CONFIDENCE_UNKNOWN(true, "OCR_CONFIDENCE_UNKNOWN"),

    /**
     * No OCR engine is deployed / configured. Not a failure of the asset and
     * not something a retry can fix, so it does not raise an issue either —
     * it is the documented "text layer only" operating mode.
     */
    SKIPPED_NO_ENGINE(false, null),

    /**
     * The OCR engine could not be started or reached (missing executable,
     * sidecar down, connection refused, temp dir unwritable). Operator fix,
     * not a content problem: a retry only helps once the deployment is fixed.
     */
    UNAVAILABLE(true, "OCR_UNAVAILABLE"),

    /** The OCR engine exceeded its wall-clock deadline and was killed. */
    TIMEOUT(true, "OCR_TIMEOUT"),

    /** The OCR engine ran and reported failure (non-zero exit / HTTP 5xx). */
    PROCESS_FAILED(true, "OCR_PROCESS_FAILED"),

    /**
     * stdout or the result file passed the configured byte ceiling. The
     * output is not read further: a 400 MB dump neither parses nor belongs
     * in a log.
     */
    OUTPUT_TOO_LARGE(true, "OCR_OUTPUT_TOO_LARGE"),

    /**
     * The engine answered within limits, but its output could not be parsed
     * into text. Distinct from {@link #NO_TEXT}: the content is unknown,
     * not empty.
     */
    OUTPUT_UNPARSEABLE(true, "OCR_OUTPUT_UNPARSEABLE");

    private final boolean needsReview;
    private final String stableCode;

    OcrOutcome(boolean needsReview, String stableCode) {
        this.needsReview = needsReview;
        this.stableCode = stableCode;
    }

    /**
     * @return true when the pipeline must flag the source for human review
     *         (existing {@code OCR_LOW_CONFIDENCE} issue producer) instead of
     *         publishing it silently
     */
    public boolean needsReview() {
        return needsReview;
    }

    /** @return stable client-facing code, or null for the non-failure outcomes */
    public String stableCode() {
        return stableCode;
    }

    /**
     * @return true for the failure class (engine could not deliver a usable
     *         result), which {@code on-failure=fail} turns into a typed
     *         ingestion failure
     */
    public boolean isFailure() {
        return stableCode != null && this != CONFIDENCE_UNKNOWN;
    }

    /**
     * Reverse lookup used when parsing an engine response that echoes the
     * outcome name.
     *
     * @return the matching outcome, or {@link #OUTPUT_UNPARSEABLE} for an
     *         unknown label — an unrecognised outcome must never be read as OK
     */
    public static OcrOutcome fromLabel(String label) {
        if (label != null) {
            for (OcrOutcome outcome : values()) {
                if (outcome.name().equalsIgnoreCase(label.trim())) {
                    return outcome;
                }
            }
        }
        return OUTPUT_UNPARSEABLE;
    }
}
