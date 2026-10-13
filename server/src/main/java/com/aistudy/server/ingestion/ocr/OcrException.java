package com.aistudy.server.ingestion.ocr;

/**
 * OCR-stage failure carrying an explicit {@link OcrOutcome}.
 *
 * <p>Thrown instead of returning {@code null} or an empty result, so the
 * extraction services can tell "genuinely no text" (a normal
 * {@link OcrResult} with {@link OcrOutcome#NO_TEXT}) apart from "the engine
 * could not run" (this exception).
 *
 * <p>The message is operator-facing but never contains the image path, the
 * storage key or engine output beyond the bounded, redacted diagnostics the
 * engine chose to keep.
 */
public class OcrException extends RuntimeException {

    private final OcrOutcome outcome;
    private final String diagnostics;

    public OcrException(String message) {
        this(OcrOutcome.PROCESS_FAILED, message, null, null);
    }

    public OcrException(String message, Throwable cause) {
        this(OcrOutcome.PROCESS_FAILED, message, null, cause);
    }

    /**
     * @param outcome    bounded failure class, must not be null
     * @param message    safe, bounded text for logs and job diagnostics
     * @param diagnostics optional redacted / truncated engine output for the
     *                    server log only; never exposed to the client
     * @param cause      underlying failure, nullable
     */
    public OcrException(OcrOutcome outcome, String message, String diagnostics, Throwable cause) {
        super(message, cause);
        this.outcome = outcome == null ? OcrOutcome.PROCESS_FAILED : outcome;
        this.diagnostics = diagnostics;
    }

    public OcrOutcome outcome() {
        return outcome;
    }

    /** @return the stable client-facing code, e.g. {@code OCR_TIMEOUT} */
    public String stableCode() {
        return outcome.stableCode() == null ? outcome.name() : outcome.stableCode();
    }

    /**
     * @return redacted engine diagnostics for the server log, nullable;
     *         callers MUST NOT put this in a client-visible field
     */
    public String diagnostics() {
        return diagnostics;
    }
}
