package com.aistudy.server.ingestion.ocr;

/**
 * What the pipeline does when an OCR run fails outright
 * ({@link OcrOutcome#UNAVAILABLE}, {@link OcrOutcome#TIMEOUT},
 * {@link OcrOutcome#PROCESS_FAILED}, {@link OcrOutcome#OUTPUT_TOO_LARGE},
 * {@link OcrOutcome#OUTPUT_UNPARSEABLE}).
 *
 * <p>Configuration key {@code aistudy.ocr.on-failure}. Two values, no more: a
 * richer policy would just move the ambiguity from the code to the config.
 */
public enum OcrFailurePolicy {

    /**
     * Default, and the only backward-compatible behaviour: keep the page that
     * was produced (text absent, confidence absent), mark the source for human
     * review so the existing {@code OCR_LOW_CONFIDENCE} issue producer fires,
     * and let the job finish as NEEDS_REVIEW / PARTIAL_FAILED when another
     * asset failed too. Chosen as the default because OCR is optional in this
     * deployment shape and one unavailable sidecar must not destroy an
     * otherwise readable 300-page PDF.
     */
    REVIEW,

    /**
     * Fail the asset with a typed {@link com.aistudy.server.ingestion.job.service.IngestionFailure}
     * so the job lands in FAILED (all assets) or PARTIAL_FAILED (some assets)
     * with a diagnosable code. For deployments where OCR is a product promise:
     * silently publishing a scanned page with no text is the worse outcome.
     */
    FAIL
}
