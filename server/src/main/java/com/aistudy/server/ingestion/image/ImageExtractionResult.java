package com.aistudy.server.ingestion.image;

import com.aistudy.server.ingestion.ocr.OcrOutcome;

/**
 * Bounded, explicit outcome of one image extraction (F-28).
 *
 * <p>The job pipeline only consumes the legacy boolean
 * ({@link #needsReview()}) today; the remaining fields exist so the outcome is
 * assertable and diagnosable instead of inferred from side effects, and so a
 * later pipeline can surface per-asset results without inventing a new
 * vocabulary. No paths, no engine output, nothing client-unsafe in here.
 *
 * @param pageId     persisted {@code source_page} id, null only if nothing was
 *                   written (which cannot happen for a returned result)
 * @param pageOrder  Source-wide reading position assigned to this page
 * @param width      measured image width in pixels, always {@code >= 1}
 * @param height     measured image height in pixels, always {@code >= 1}
 * @param format     {@code PNG}, {@code JPEG}, {@code WEBP} or the submitted
 *                   MIME type when a JDK reader handled the stream
 * @param ocrOutcome how the OCR stage ended; never null
 * @param confidence mean OCR confidence in {@code 0.0..1.0}, or null when the
 *                   engine could not report one (stored as SQL NULL, never
 *                   defaulted to a confident value)
 * @param textChars  characters of extracted text persisted for this page
 * @param needsReview true when the source must go to human review
 */
public record ImageExtractionResult(Long pageId,
                                   int pageOrder,
                                   int width,
                                   int height,
                                   String format,
                                   OcrOutcome ocrOutcome,
                                   Double confidence,
                                   int textChars,
                                   boolean needsReview) {
}
