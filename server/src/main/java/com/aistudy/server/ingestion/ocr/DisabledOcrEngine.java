package com.aistudy.server.ingestion.ocr;

import java.io.File;

/**
 * Stand-in engine for deployments without OCR.
 *
 * <p>Keeps exactly one {@link OcrEngine} bean in the context so the image and
 * PDF extraction services are always constructible, and reports
 * {@link #available()} false so those services record
 * {@link OcrOutcome#SKIPPED_NO_ENGINE} instead of pretending an OCR pass
 * happened. It is the answer to "what does the app do when OCR is not
 * configured": text-layer PDFs still ingest normally, image assets ingest with
 * no text and are NOT marked failed, and nothing blocks waiting on a binary
 * that does not exist.
 */
public class DisabledOcrEngine implements OcrEngine {

    @Override
    public OcrResult ocr(File imageFile) {
        throw new OcrException(OcrOutcome.UNAVAILABLE,
                "OCR is disabled (aistudy.ocr.enabled=false or engine=none)", null, null);
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String name() {
        return "none";
    }
}
