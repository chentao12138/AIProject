package com.aistudy.server.ingestion.ocr;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-09 unit tests for the two pure pieces behind the OCR fences: bounded
 * stream capture ({@link BoundedTextCaptor}) and log-safe diagnostics
 * reduction ({@link OcrDiagnostics}).
 */
class OcrStageGuardsTest {

    @Test
    void captorKeepsAtMostTheConfiguredBytesButDrainsToEof() throws IOException {
        byte[] payload = new byte[10_000];
        java.util.Arrays.fill(payload, (byte) 'q');
        BoundedTextCaptor captor = new BoundedTextCaptor(1000);
        captor.drain(new ByteArrayInputStream(payload));

        assertEquals(1000, captor.capturedBytes(), "the cap is a hard ceiling");
        assertTrue(captor.truncated(), "and truncation must be visible to the caller");
        assertFalse(captor.ioError());
        assertEquals(1000, captor.text().length());
    }

    @Test
    void captorDecodesUtf8OnceSoSplitMultibyteStaysReadable() throws IOException {
        byte[] text = "识别文字".getBytes(StandardCharsets.UTF_8);
        BoundedTextCaptor captor = new BoundedTextCaptor(text.length);
        captor.drain(new ByteArrayInputStream(text));
        assertEquals("识别文字", captor.text());
        assertFalse(captor.truncated());
    }

    @Test
    void zeroCapStillReportsThatDataWasAvailable() throws IOException {
        BoundedTextCaptor captor = new BoundedTextCaptor(0);
        captor.drain(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        assertTrue(captor.truncated());
        assertEquals(0, captor.capturedBytes());
    }

    @Test
    void streamFailureIsRecordedNotThrownOutOfTheDrainThread() throws IOException {
        BoundedTextCaptor captor = new BoundedTextCaptor(100);
        captor.drain(new ByteArrayInputStream("partial".getBytes(StandardCharsets.UTF_8)));
        captor.markIoError();
        assertTrue(captor.ioError());
        assertEquals("partial", captor.text(), "whatever was captured stays usable");
    }

    @Test
    void diagnosticsAreTruncatedAndMarked() {
        String longText = "e".repeat(500);
        String redacted = OcrDiagnostics.redact(longText, 100, List.of());
        assertEquals(100, redacted.length(), "the ceiling is honoured exactly");
        assertTrue(redacted.endsWith("..."), redacted);
    }

    @Test
    void diagnosticsDropControlCharactersAndFlattenNewlines() {
        String redacted = OcrDiagnostics.redact("line1\nline2\ttail", 200, List.of());
        assertFalse(redacted.contains("\n"));
        assertFalse(redacted.contains("\t"));
        assertTrue(redacted.startsWith("line1 line2 tail"), redacted);
        assertEquals("", OcrDiagnostics.redact(null, 200, List.of()));
        assertEquals("", OcrDiagnostics.redact("   ", 200, List.of()));
    }

    @Test
    void knownLocalPathsAreMaskedOutOfDiagnostics() {
        String raw = "could not write /tmp/aistudy/ocr/ocr-result-123.txt for C:\\Users\\me\\img.png";
        String redacted = OcrDiagnostics.redact(raw, 300, List.of("/tmp/aistudy/ocr/ocr-result-123.txt"));
        assertFalse(redacted.contains("ocr-result-123"), redacted);
        assertFalse(redacted.contains("Users"), "generic absolute paths are masked too: " + redacted);
    }

    @Test
    void redactionNeverExceedsTheCeilingEvenWithAMarker() {
        String redacted = OcrDiagnostics.redact("x".repeat(50), 6, List.of());
        assertEquals(6, redacted.length());
    }
}
