package com.aistudy.server.ingestion.image;

import com.aistudy.server.ingestion.job.service.IngestionErrorCode;

/**
 * BUSINESS-021 — thrown when image extraction fails in a controlled way.
 *
 * <p>Carries a stable {@link IngestionErrorCode} and a safe message that
 * may be persisted in the ingestion job error fields without leaking
 * parser internals, filesystem paths, or binary contents.
 */
public class ImageExtractionException extends RuntimeException {

    private final IngestionErrorCode errorCode;

    public ImageExtractionException(IngestionErrorCode errorCode, String safeMessage) {
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
