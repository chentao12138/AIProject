package com.aistudy.server.ingestion.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-09 unit tests: the OCR child process must be fenced by a deadline, its
 * output must stay inside the configured byte ceilings, a confidence parse
 * failure must never read as "fully confident", and diagnostics must be
 * truncated.
 *
 * <p>No PaddleOCR binary and no database: the process is started through the
 * {@code startProcess} seam with a mocked {@link Process}, so the assertions
 * cover the engine's own fences rather than the OCR library.
 */
class PaddleOcrProcessEngineTest {

    @TempDir
    Path tempDir;

    private File image() throws IOException {
        File file = tempDir.resolve("input.png").toFile();
        Files.writeString(file.toPath(), "not really a png");
        return file;
    }

    /** Engine that returns a prepared mock process instead of launching anything. */
    private static class Fake extends PaddleOcrProcessEngine {
        Process process;
        IOException startError;
        java.util.function.Consumer<String> onLaunch;

        Fake(String command, int maxStdout, long maxOutput, Duration timeout, String tempDir) {
            super(command, tempDir, timeout, Duration.ofMillis(20), maxStdout,
                    org.springframework.util.unit.DataSize.ofBytes(maxOutput), 200);
        }

        @Override
        Process startProcess(ProcessBuilder pb) throws IOException {
            if (startError != null) {
                throw startError;
            }
            if (onLaunch != null) {
                // The last argument is the --output path the engine prepared.
                onLaunch.accept(pb.command().get(pb.command().size() - 1));
            }
            return process;
        }
    }

    @Test
    void timeoutKillsTheChildAndReportsTimeout() throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);

        Fake fake = new Fake("paddleocr", 4096, 4096, Duration.ofMillis(120), tempDir.toString());
        fake.process = process;

        OcrException error = assertThrows(OcrException.class, () -> fake.ocr(image()));
        assertEquals(OcrOutcome.TIMEOUT, error.outcome());
        assertEquals("OCR_TIMEOUT", error.stableCode());
        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
        // The kill fence must escalate: TERM first, then KILL when it is ignored.
        verify(process).destroy();
        verify(process).destroyForcibly();
    }

    @Test
    void nonZeroExitIsAProcessFailureWithBoundedDiagnostics() throws Exception {
        String noisy = "Traceback ...\n" + "x".repeat(20000) + "\nconfidence: 0.9";
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(
                new ByteArrayInputStream(noisy.getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(3);

        Fake fake = new Fake("paddleocr", 4096, 4096, Duration.ofSeconds(5), tempDir.toString());
        fake.process = process;

        OcrException error = assertThrows(OcrException.class, () -> fake.ocr(image()));
        assertEquals(OcrOutcome.PROCESS_FAILED, error.outcome());
        assertTrue(error.getMessage().contains("exit code 3"), error.getMessage());
        assertNotNull(error.diagnostics());
        assertTrue(error.diagnostics().length() <= 200 + 3,
                "diagnostics must be truncated, was " + error.diagnostics().length());
        assertFalse(error.diagnostics().contains(tempDir.toString()),
                "local temp paths must be masked out of diagnostics");
    }

    @Test
    void oversizedStdoutFailsInsteadOfBeingReadIntoMemory() throws Exception {
        byte[] flood = new byte[8192];
        java.util.Arrays.fill(flood, (byte) 'a');
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(flood));
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(counting);
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(0);

        Fake fake = new Fake("paddleocr", 1024, 4096, Duration.ofSeconds(5), tempDir.toString());
        fake.process = process;

        OcrException error = assertThrows(OcrException.class, () -> fake.ocr(image()));
        assertEquals(OcrOutcome.OUTPUT_TOO_LARGE, error.outcome());
        assertTrue(error.getMessage().contains("stdout ceiling"), error.getMessage());
        // Draining to EOF past the cap is what prevents the child from blocking
        // on a full pipe, so the whole stream must have been consumed.
        assertEquals(flood.length, counting.readBytes);
    }

    /** Counts every byte handed to the captor, so draining can be asserted. */
    private static class CountingInputStream extends InputStream {
        private final InputStream delegate;
        int readBytes;

        CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                readBytes += n;
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            int n = delegate.read();
            if (n >= 0) {
                readBytes++;
            }
            return n;
        }
    }

    @Test
    void missingExecutableIsUnavailableNotContentFailure() {
        Fake fake = new Fake("definitely-not-an-installed-ocr-binary-xyz", 4096, 4096,
                Duration.ofSeconds(5), tempDir.toString());
        fake.startError = new IOException("Cannot run program");

        OcrException error = assertThrows(OcrException.class,
                () -> fake.ocr(tempDir.resolve("missing.png").toFile()));
        assertEquals(OcrOutcome.UNAVAILABLE, error.outcome());
        assertEquals("OCR_UNAVAILABLE", error.stableCode());
        assertFalse(error.getMessage().contains(tempDir.toString()),
                "the safe message must not leak the server filesystem");
    }

    @Test
    void oversizedResultFileIsRejectedBeforeBeingRead() throws Exception {
        Fake fake = new Fake("paddleocr", 4096, 1024, Duration.ofSeconds(5), tempDir.toString());
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(
                new ByteArrayInputStream("done\n".getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        fake.onLaunch = outputPath -> {
            try {
                byte[] huge = new byte[4096];
                java.util.Arrays.fill(huge, (byte) 'z');
                Files.write(Path.of(outputPath), huge);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        fake.process = process;

        OcrException error = assertThrows(OcrException.class, () -> fake.ocr(image()));
        assertEquals(OcrOutcome.OUTPUT_TOO_LARGE, error.outcome());
        assertTrue(error.getMessage().contains("size limit"), error.getMessage());
    }

    @Test
    void unparsedConfidenceIsUnknownNeverFullConfidence() throws Exception {
        Fake fake = new Fake("paddleocr", 4096, 4096, Duration.ofSeconds(5), tempDir.toString());
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(
                new ByteArrayInputStream("confidence: not-a-number\n".getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        fake.onLaunch = outputPath -> {
            try {
                Files.writeString(Path.of(outputPath), "识别出来的文字");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        fake.process = process;

        OcrEngine.OcrResult result = fake.ocr(image());
        assertTrue(Double.isNaN(result.confidence()), "unknown confidence must be NaN");
        assertEquals(OcrOutcome.CONFIDENCE_UNKNOWN, result.outcome());
        assertTrue(result.needsReview(), "unknown confidence must go to human review");
        assertEquals("识别出来的文字", result.text());
    }

    @Test
    void emptyResultWithGoodConfidenceIsNoText() throws Exception {
        Fake fake = new Fake("paddleocr", 4096, 4096, Duration.ofSeconds(5), tempDir.toString());
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(
                new ByteArrayInputStream("confidence: 0.95\n".getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        fake.process = process;

        OcrEngine.OcrResult result = fake.ocr(image());
        assertEquals("", result.text());
        assertEquals(OcrOutcome.NO_TEXT, result.outcome());
        assertEquals(0.95, result.confidence());
    }

    @Test
    void parseConfidenceAcceptsOnlyFiniteValuesInRange() {
        assertEquals(0.87, PaddleOcrProcessEngine.parseConfidence("something\nconfidence: 0.87"));
        // the last usable value wins, garbage lines are skipped
        assertEquals(0.4, PaddleOcrProcessEngine.parseConfidence(
                "confidence: abc\nconfidence = 0.4\n"));
        assertTrue(Double.isNaN(PaddleOcrProcessEngine.parseConfidence("no metrics here")));
        assertTrue(Double.isNaN(PaddleOcrProcessEngine.parseConfidence(null)));
        assertTrue(Double.isNaN(PaddleOcrProcessEngine.parseConfidence("confidence:   ")));
        // out of range is not "close enough": a percent-scaled 87 is unknown
        assertTrue(Double.isNaN(PaddleOcrProcessEngine.parseConfidence("confidence: 87")));
        assertTrue(Double.isNaN(PaddleOcrProcessEngine.parseConfidence("confidence: NaN")));
        assertTrue(Double.isNaN(PaddleOcrProcessEngine.parseConfidence("confidence: Infinity")));
    }

    @Test
    void unavailableBinaryIsReportedByAvailableProbe() {
        Fake fake = new Fake("definitely-not-an-installed-ocr-binary-xyz", 4096, 4096,
                Duration.ofSeconds(5), tempDir.toString());
        assertFalse(fake.available());
        assertFalse(PaddleOcrProcessEngine.isExecutableResolvable(null));
        assertFalse(PaddleOcrProcessEngine.isExecutableResolvable("  "));
        // A path we know exists proves the probe itself works, on every OS.
        String javaHome = System.getProperty("java.home");
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        assertTrue(PaddleOcrProcessEngine.isExecutableResolvable(
                Path.of(javaHome, "bin", exe).toString()));
    }

    @Test
    void uncreatableResultDirIsUnavailableNotAHang() throws Exception {
        // A regular file standing where the result directory should be: mkdirs()
        // deterministically fails on every platform, no permissions needed.
        Path blocker = tempDir.resolve("blocked");
        Files.writeString(blocker, "not a directory");
        PaddleOcrProcessEngine engine = new PaddleOcrProcessEngine("paddleocr",
                blocker.resolve("nested").toString(), Duration.ofSeconds(5), Duration.ofMillis(20), 4096,
                org.springframework.util.unit.DataSize.ofBytes(4096), 200);

        OcrException error = assertThrows(OcrException.class, () -> engine.ocr(image()));
        assertEquals(OcrOutcome.UNAVAILABLE, error.outcome());
        assertTrue(error.getMessage().contains("directory"), error.getMessage());
    }

    @Test
    void defaultsClampNonsensicalFences() {
        PaddleOcrProcessEngine engine = new PaddleOcrProcessEngine("paddleocr",
                tempDir.toString(), Duration.ofSeconds(-5), Duration.ZERO, 1,
                org.springframework.util.unit.DataSize.ofBytes(1), 1);
        assertEquals(1024, engine.maxStdoutBytes(), "stdout ceiling has a floor");
        assertEquals(1024L, engine.maxOutputBytes(), "result ceiling has a floor");
        assertEquals(Duration.ofMinutes(2), engine.timeout(),
                "a zero or negative deadline must not disable the fence");
        assertFalse(engine.available(), "the bogus command is not installed");
    }

    @Test
    void engineKeepsItsOwnIdentity() {
        Fake fake = new Fake("paddleocr", 4096, 4096, Duration.ofSeconds(5), tempDir.toString());
        assertEquals("PaddleOCR", fake.name());
    }
}
