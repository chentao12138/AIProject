package com.aistudy.server.ingestion.extract;

import com.aistudy.server.ingestion.job.service.IngestionErrorCode;

/**
 * BUSINESS-006 — typed content-ingestion failure carrying a stable
 * error code and a SAFE message (no stack trace, no absolute paths).
 *
 * <p>Thrown by the deterministic text parser / extractor for CONTENT
 * problems (bad encoding, document too large). The ingestion job
 * layer catches it and persists a FAILED job with
 * {@link IngestionErrorCode} + {@link #safeMessage()}. Environment
 * failures (storage IO) are NOT this exception — they propagate as
 * {@link IllegalStateException} and fail the request instead.
 */
public class IngestionParseException extends RuntimeException {

    private final IngestionErrorCode errorCode;
    private final String safeMessage;

    public IngestionParseException(IngestionErrorCode errorCode, String safeMessage) {
        super(safeMessage);
        this.errorCode = errorCode;
        this.safeMessage = safeMessage;
    }

    public IngestionErrorCode errorCode() {
        return errorCode;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
