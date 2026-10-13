package com.aistudy.server.ingestion.image;

/**
 * Thrown by {@link WebpHeaderReader} for a WebP container whose RIFF structure
 * or dimension header cannot be trusted.
 *
 * <p>Carries only a safe, bounded reason string (no offsets of user data, no
 * file paths): the extraction service maps it to the same stable
 * {@code INVALID_IMAGE} semantics used for PNG/JPEG magic mismatches, so a
 * truncated WebP fails the job instead of being stored with fake metadata.
 */
public class WebpMetadataException extends RuntimeException {

    public WebpMetadataException(String safeReason) {
        super(safeReason);
    }
}
