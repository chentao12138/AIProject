package com.aistudy.server.ingestion.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Component
public class PaddleOcrProcessEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(PaddleOcrProcessEngine.class);

    private final String paddleOcrCommand;
    private final String ocrTempDir;

    public PaddleOcrProcessEngine(@Value("${aistudy.ocr.paddle-ocr-command:paddleocr}") String paddleOcrCommand,
                                  @Value("${aistudy.ocr.temp-dir:${java.io.tmpdir}/aistudy/ocr}") String ocrTempDir) {
        this.paddleOcrCommand = paddleOcrCommand;
        this.ocrTempDir = ocrTempDir;
    }

    @Override
    public OcrResult ocr(File imageFile) {
        File tempDir = new File(ocrTempDir);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new OcrException("Failed to create OCR temp directory: " + tempDir);
        }
        File outputFile;
        try {
            outputFile = File.createTempFile("ocr-result-", ".txt", tempDir);
        } catch (java.io.IOException e) {
            throw new OcrException("Failed to create OCR output file", e);
        }
        List<String> command = new ArrayList<>();
        command.add(paddleOcrCommand);
        command.add("--image_dir");
        command.add(imageFile.getAbsolutePath());
        command.add("--output");
        command.add(outputFile.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        long start = System.currentTimeMillis();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                StringBuilder stdout = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append('\n');
                }
                int exit = process.waitFor();
                long latencyMs = System.currentTimeMillis() - start;
                if (exit != 0) {
                    log.warn("PaddleOCR exited with code {}: {}", exit, stdout);
                    throw new OcrException("PaddleOCR failed with exit code " + exit);
                }
                String resultText = new String(Files.readAllBytes(outputFile.toPath()));
                double confidence = parseConfidence(stdout.toString());
                return new OcrResult(resultText, confidence, "PaddleOCR", null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OcrException("OCR interrupted", e);
            }
        } catch (java.io.IOException e) {
            throw new OcrException("Failed to execute OCR command", e);
        } finally {
            outputFile.delete();
        }
    }

    private double parseConfidence(String stdout) {
        try {
            String[] lines = stdout.split("\n");
            for (String line : lines) {
                if (line.contains("confidence")) {
                    String[] parts = line.split(":");
                    return Double.parseDouble(parts[parts.length - 1].trim());
                }
            }
        } catch (Exception ignored) {
        }
        return 1.0;
    }
}
