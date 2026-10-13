package com.aistudy.server.ingestion.job.service;

/**
 * Contract shared by every typed content-ingestion failure.
 *
 * <p>The job worker persists a FAILED job from these two accessors: without a
 * shared type it could only stamp the generic {@code INGESTION_FAILED}, which
 * made a blocked zip-slip attempt indistinguishable from an internal error for
 * both the client and the tests that assert on {@code errorCode}.
 */
public interface IngestionFailure {

    IngestionErrorCode errorCode();

    /** Client-safe text: no stack trace, no absolute path, no storage key. */
    String safeMessage();
}
