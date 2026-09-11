package com.aistudy.server.ingestion.job.service;

/**
 * BUSINESS-005/006 — stable machine-readable ingestion error codes
 * (content-ingestion.md §6: {@code errorCode / safeErrorMessage}).
 *
 * <p>The client contract only ever sees the code + a SAFE message —
 * never a stack trace. Codes are stable strings so clients can branch
 * on them; add codes per pipeline step rather than reusing generic
 * ones.
 */
public enum IngestionErrorCode {

    /** The source asset is a ZIP that failed safety inspection. */
    ZIP_SAFETY_VIOLATION("ZIP_SAFETY_VIOLATION"),

    /** The text document is not valid UTF-8 (GBK/GB18030 deferred). */
    ENCODING_ERROR("ENCODING_ERROR"),

    /** The text document exceeds the configured text ingestion limit. */
    DOCUMENT_TOO_LARGE("DOCUMENT_TOO_LARGE"),

    /** The asset format has no ingestion pipeline (defensive; create
     * rejects such formats up front with 422 INGESTION_NOT_READY). */
    UNSUPPORTED_FORMAT("UNSUPPORTED_FORMAT");

    private final String code;

    IngestionErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
