package com.aistudy.server.ingestion.pdf;

import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.ingestion.job.service.IngestionFailure;

/**
 * BUSINESS-020 — thrown when PDF extraction fails in a controlled way.
 *
 * <p>Carries a stable {@link IngestionErrorCode} and a safe message that
 * may be persisted in the ingestion job error fields without leaking
 * parser internals, filesystem paths, or document text.
 */
public class PdfExtractionException extends RuntimeException implements IngestionFailure {

    private final IngestionErrorCode errorCode;

    public PdfExtractionException(IngestionErrorCode errorCode, String safeMessage) {
        super(safeMessage);
        this.errorCode = errorCode;
    }

    public IngestionErrorCode errorCode() {
        return errorCode;
    }

    public String safeMessage() {
        return super.getMessage();
    }
}
