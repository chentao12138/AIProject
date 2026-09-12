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
    UNSUPPORTED_FORMAT("UNSUPPORTED_FORMAT"),

    /** The PDF asset could not be read/parsed (encrypted / corrupt / not a PDF). */
    INVALID_PDF("INVALID_PDF"),

    /** The PDF is encrypted and cannot be processed without a password. */
    PDF_ENCRYPTED("PDF_ENCRYPTED"),

    /** The PDF exceeds the configured page limit. */
    PDF_PAGE_LIMIT_EXCEEDED("PDF_PAGE_LIMIT_EXCEEDED"),

    /** The PDF exceeds the configured byte / extracted character limit. */
    PDF_TEXT_LIMIT_EXCEEDED("PDF_TEXT_LIMIT_EXCEEDED"),

    /** PDF parsing failed after loading. */
    PDF_PARSE_FAILED("PDF_PARSE_FAILED"),

    /** The image asset is not a valid PNG/JPEG image. */
    INVALID_IMAGE("INVALID_IMAGE"),

    /** The image exceeds the configured width/height limit. */
    IMAGE_DIMENSION_LIMIT_EXCEEDED("IMAGE_DIMENSION_LIMIT_EXCEEDED"),

    /** The image exceeds the configured pixel count limit. */
    IMAGE_PIXEL_LIMIT_EXCEEDED("IMAGE_PIXEL_LIMIT_EXCEEDED"),

    /** The image exceeds the configured byte limit. */
    IMAGE_TOO_LARGE("IMAGE_TOO_LARGE"),

    /** Image metadata parsing failed after loading. */
    IMAGE_PARSE_FAILED("IMAGE_PARSE_FAILED");

    private final String code;

    IngestionErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
