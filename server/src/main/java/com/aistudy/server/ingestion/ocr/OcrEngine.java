package com.aistudy.server.ingestion.ocr;

import java.io.File;

public interface OcrEngine {

    OcrResult ocr(File imageFile);

    record OcrResult(String text, double confidence, String engine, String model) {
    }
}
