package com.aistudy.server.ingestion.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PaddleOCR driven as a local child process (F-09 hardened).
 *
 * <h3>Process protocol (documented contract)</h3>
 *
 * <pre>
 *   ${aistudy.ocr.paddle-ocr-command} --image_dir &lt;absolute local image path&gt; \
 *                                      --output    &lt;absolute local result path&gt;
 * </pre>
 *
 * <ul>
 *   <li>stdout+stderr are merged ({@code redirectErrorStream(true)}) and are
 *       diagnostics only. The recognised text is read from the
 *       {@code --output} file.</li>
 *   <li>exit code {@code 0} = success; anything else =
 *       {@link OcrOutcome#PROCESS_FAILED}.</li>
 *   <li>an optional line matching {@code confidence:\s*<number>} in stdout
 *       supplies the mean confidence, expected in {@code 0.0..1.0}. A missing
 *       or unparseable value is {@link OcrEngine#CONFIDENCE_UNKNOWN} — never
 *       a fabricated {@code 1.0}, which used to make a broken build look like
 *       a perfect scan.</li>
 *   <li>the adapter binary is deployment-provided (see
 *       {@code deploy/ocr} and {@code docs/operations-ocr-docker.md}); this
 *       class only speaks the protocol above.</li>
 * </ul>
 *
 * <h3>Safety fences</h3>
 *
 * <ul>
 *   <li><b>wall clock</b>: {@code aistudy.ocr.timeout}. On expiry the child is
 *       {@code destroy()}d, given {@code aistudy.ocr.kill-grace} to exit, then
 *       {@code destroyForcibly()}d, and the job fails with
 *       {@link OcrOutcome#TIMEOUT}. Without this an OCR hang occupies an
 *       ingestion worker until the whole job's claim goes stale.</li>
 *   <li><b>stdout size</b>: {@code aistudy.ocr.max-stdout-bytes}. The stream is
 *       still drained to EOF so the child cannot block on a full pipe.</li>
 *   <li><b>result size</b>: {@code aistudy.ocr.max-output-bytes}. The file is
 *       size-checked before reading, so a runaway writer is never loaded into
 *       the heap. Note the ceiling is also the correctness bound: past it the
 *       job fails with {@link OcrOutcome#OUTPUT_TOO_LARGE} instead of
 *       publishing half a document.</li>
 *   <li><b>log size</b>: {@code aistudy.ocr.max-diagnostics-chars}, path-masked
 *       and control-char-stripped via {@link OcrDiagnostics}.</li>
 * </ul>
 *
 * <p>Not thread-safe by design: one run per call, one call per asset page.
 */
public class PaddleOcrProcessEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(PaddleOcrProcessEngine.class);

    /** Upper bound on the deadline value; guards against overflow in toMillis(). */
    private static final Duration MAX_TIMEOUT = Duration.ofHours(6);

    private final String paddleOcrCommand;
    private final String ocrTempDir;
    private final Duration timeout;
    private final Duration killGrace;
    private final int maxStdoutBytes;
    private final long maxOutputBytes;
    private final int maxDiagnosticsChars;

    /**
     * @param paddleOcrCommand      executable implementing the protocol above
     * @param ocrTempDir            directory for the per-run result files
     * @param timeout               wall-clock deadline for one OCR run
     * @param killGrace             patience between SIGTERM and SIGKILL
     * @param maxStdoutBytes        ceiling for merged stdout+stderr
     * @param maxOutputBytes        ceiling for the result file
     * @param maxDiagnosticsChars   ceiling for any diagnostic kept or logged
     */
    public PaddleOcrProcessEngine(String paddleOcrCommand,
                                  String ocrTempDir,
                                  Duration timeout,
                                  Duration killGrace,
                                  int maxStdoutBytes,
                                  DataSize maxOutputBytes,
                                  int maxDiagnosticsChars) {
        this.paddleOcrCommand = paddleOcrCommand;
        this.ocrTempDir = ocrTempDir;
        this.timeout = clampTimeout(timeout);
        this.killGrace = clampTimeout(killGrace);
        this.maxStdoutBytes = Math.max(1024, maxStdoutBytes);
        this.maxOutputBytes = Math.max(1024L, maxOutputBytes.toBytes());
        this.maxDiagnosticsChars = Math.max(64, maxDiagnosticsChars);
    }

    private static Duration clampTimeout(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return Duration.ofMinutes(2);
        }
        return requested.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : requested;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Never returns null. Failure modes are exactly the
     * {@link OcrOutcome} set: {@code UNAVAILABLE} (binary or temp dir
     * unusable), {@code TIMEOUT}, {@code PROCESS_FAILED},
     * {@code OUTPUT_TOO_LARGE}, {@code OUTPUT_UNPARSEABLE}, plus a normal
     * result whose outcome may be {@code NO_TEXT} or
     * {@code CONFIDENCE_UNKNOWN}.
     */
    @Override
    public OcrResult ocr(File imageFile) {
        File tempDir = new File(ocrTempDir);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR result directory is not writable", null, null);
        }
        File outputFile;
        try {
            outputFile = File.createTempFile("ocr-result-", ".txt", tempDir);
        } catch (IOException e) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR result file could not be created", null, e);
        }

        List<String> command = new ArrayList<>();
        command.add(paddleOcrCommand);
        command.add("--image_dir");
        command.add(imageFile.getAbsolutePath());
        command.add("--output");
        command.add(outputFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        long startNanos = System.nanoTime();
        try {
            Process process;
            try {
                process = startProcess(pb);
            } catch (IOException e) {
                // Missing executable is the single most common deployment
                // mistake; it must not look like a content failure.
                throw new OcrException(OcrOutcome.UNAVAILABLE,
                        "OCR executable is not available: " + safeCommandLabel(paddleOcrCommand), null, e);
            }

            BoundedTextCaptor stdout = new BoundedTextCaptor(maxStdoutBytes);
            Thread drain = startDrainThread(process.getInputStream(), stdout);
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    List<String> mask = List.of(imageFile.getAbsolutePath(), outputFile.getAbsolutePath());
                    String diagnostics = OcrDiagnostics.redact(
                            terminate(process, stdout, drain), maxDiagnosticsChars, mask);
                    log.warn("PaddleOCR run killed after {} (exit fence applied), diagnostics={}",
                            timeout, diagnostics);
                    throw new OcrException(OcrOutcome.TIMEOUT,
                            "OCR timed out after " + timeout.toSeconds() + "s and was terminated",
                            diagnostics, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminate(process, stdout, drain);
                throw new OcrException(OcrOutcome.PROCESS_FAILED,
                        "OCR was interrupted, child process terminated", null, e);
            } finally {
                joinQuietly(drain);
            }

            int exit = safeExitValue(process);
            if (stdout.truncated()) {
                throw new OcrException(OcrOutcome.OUTPUT_TOO_LARGE,
                        "OCR diagnostics exceeded the configured stdout ceiling of "
                                + maxStdoutBytes + " bytes",
                        OcrDiagnostics.redact(stdout.text(), maxDiagnosticsChars,
                                List.of(imageFile.getAbsolutePath(), outputFile.getAbsolutePath())),
                        null);
            }
            if (exit != 0) {
                String diagnostics = OcrDiagnostics.redact(stdout.text(), maxDiagnosticsChars,
                        List.of(imageFile.getAbsolutePath(), outputFile.getAbsolutePath()));
                // Bounded + masked: the previous build dumped the whole merged
                // stream here, which is both a log-flooding and a leak risk.
                log.warn("PaddleOCR exited with code {} after {} ms: {}",
                        exit, (System.nanoTime() - startNanos) / 1_000_000L, diagnostics);
                throw new OcrException(OcrOutcome.PROCESS_FAILED,
                        "OCR failed with exit code " + exit, diagnostics, null);
            }
            if (stdout.ioError()) {
                throw new OcrException(OcrOutcome.PROCESS_FAILED,
                        "OCR stream could not be read", null, null);
            }

            String resultText = readResultFile(outputFile.toPath(), imageFile.getAbsolutePath());
            double confidence = parseConfidence(stdout.text());
            // NaN must be tested with isNaN: NaN == NaN is false, so a
            // comparison-based check would silently mis-classify unknown
            // confidence as a real score.
            OcrOutcome outcome;
            if (resultText.isBlank()) {
                outcome = OcrOutcome.NO_TEXT;
            } else if (Double.isNaN(confidence)) {
                outcome = OcrOutcome.CONFIDENCE_UNKNOWN;
            } else {
                outcome = OcrOutcome.OK;
            }
            log.debug("PaddleOCR finished in {} ms, resultChars={}",
                    (System.nanoTime() - startNanos) / 1_000_000L, resultText.length());
            return new OcrResult(resultText, confidence, "PaddleOCR", null, outcome);
        } finally {
            if (!outputFile.delete()) {
                outputFile.deleteOnExit();
            }
        }
    }

    /**
     * Seam for tests: the real implementation starts the configured child.
     *
     * @param pb prepared process builder
     * @return the started process
     * @throws IOException when the executable cannot be launched
     */
    Process startProcess(ProcessBuilder pb) throws IOException {
        return pb.start();
    }

    /**
     * @param in process stdout+stderr stream
     * @param stdout captor to fill
     * @return a started daemon thread; daemon so a wedged child can never keep
     *         the JVM alive after the kill fence fired
     */
    private Thread startDrainThread(InputStream in, BoundedTextCaptor stdout) {
        Thread thread = new Thread(() -> {
            try {
                stdout.drain(in);
            } catch (IOException e) {
                stdout.markIoError();
            } finally {
                closeQuietly(in);
            }
        }, "paddleocr-stdout-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Escalating kill fence: SIGTERM, {@code killGrace} of patience, then
     * SIGKILL. Returns whatever stdout was captured up to that point.
     *
     * @param process running child
     * @param stdout captor for the diagnostics excerpt
     * @param drain drain thread to interrupt
     * @return captured stdout+stderr, possibly empty
     */
    private String terminate(Process process, BoundedTextCaptor stdout, Thread drain) {
        process.destroy();
        try {
            if (!process.waitFor(killGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(killGrace.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            drain.interrupt();
        }
        return stdout.text();
    }

    private static void joinQuietly(Thread drain) {
        try {
            drain.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // closing a dead pipe tells us nothing worth reporting
        }
    }

    /** @return the child exit code, or -1 when the JVM never saw one */
    private static int safeExitValue(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /**
     * Bounded read of the OCR result file.
     *
     * @param path result file path
     * @param imagePath local image path, masked in any diagnostic
     * @return file contents decoded as UTF-8, blank-normalised, never null
     * @throws OcrException {@code OUTPUT_TOO_LARGE} when the file exceeds the
     *                      ceiling, {@code UNAVAILABLE} when it cannot be read
     */
    private String readResultFile(Path path, String imagePath) {
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR produced no result file", null, e);
        }
        if (size > maxOutputBytes) {
            log.warn("OCR result file rejected: {} bytes exceeds the configured {} byte ceiling",
                    size, maxOutputBytes);
            throw new OcrException(OcrOutcome.OUTPUT_TOO_LARGE,
                    "OCR result exceeds the configured size limit of " + maxOutputBytes + " bytes",
                    OcrDiagnostics.redact("resultSize=" + size, maxDiagnosticsChars,
                            List.of(imagePath)),
                    null);
        }
        byte[] raw = new byte[(int) Math.min(size, maxOutputBytes)];
        int read = 0;
        try (InputStream in = Files.newInputStream(path)) {
            while (read < raw.length) {
                int n = in.read(raw, read, raw.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
        } catch (IOException e) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR result file could not be read", null, e);
        }
        if (read < raw.length) {
            raw = java.util.Arrays.copyOf(raw, read);
        }
        return new String(raw, StandardCharsets.UTF_8).trim();
    }

    /**
     * Extracts the last {@code confidence: <value>} token from engine output.
     *
     * @param stdout captured stdout+stderr, may be null or blank
     * @return a confidence in {@code 0.0..1.0}, or
     *         {@link OcrEngine#CONFIDENCE_UNKNOWN} when absent, unparseable,
     *         non-finite or out of range — deliberately NOT a default of 1.0
     */
    static double parseConfidence(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return OcrEngine.CONFIDENCE_UNKNOWN;
        }
        double last = OcrEngine.CONFIDENCE_UNKNOWN;
        for (String line : stdout.split("\n")) {
            int idx = line.toLowerCase().indexOf("confidence");
            if (idx < 0) {
                continue;
            }
            String tail = line.substring(idx + "confidence".length());
            int colon = tail.indexOf(':');
            String candidate = (colon >= 0 ? tail.substring(colon + 1) : tail).trim();
            StringBuilder number = new StringBuilder();
            for (int i = 0; i < candidate.length(); i++) {
                char c = candidate.charAt(i);
                if (Character.isDigit(c) || c == '.' || c == '-' || c == '+') {
                    number.append(c);
                } else if (number.length() > 0) {
                    break;
                }
            }
            if (number.length() == 0) {
                continue;
            }
            try {
                double parsed = Double.parseDouble(number.toString());
                if (Double.isFinite(parsed) && parsed >= 0.0 && parsed <= 1.0) {
                    last = parsed;
                }
            } catch (NumberFormatException ignored) {
                // a malformed confidence is unknown confidence, never full confidence
            }
        }
        return last;
    }

    /**
     * @param command configured executable, may be an absolute path
     * @return only the final path segment: the client-safe message must not
     *         disclose the server filesystem layout
     */
    private static String safeCommandLabel(String command) {
        if (command == null || command.isBlank()) {
            return "unset";
        }
        String normalized = command.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 && slash + 1 < normalized.length() ? normalized.substring(slash + 1) : normalized;
    }

    /** @return true when the configured executable is resolvable on the PATH */
    @Override
    public boolean available() {
        return isExecutableResolvable(paddleOcrCommand);
    }

    @Override
    public String name() {
        return "PaddleOCR";
    }

    /**
     * Probe used by {@link #available()} and the health indicator: walk
     * {@code PATH} for an executable file. Deliberately does NOT launch the
     * child — a probe that runs OCR would double the cost of every health
     * check and could not be bounded by the OCR timeout.
     *
     * @param command configured command name or path
     * @return true when the command exists and is executable
     */
    static boolean isExecutableResolvable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        Path direct = Path.of(command);
        if (command.indexOf(File.separatorChar) >= 0 || command.contains("/")) {
            return Files.isExecutable(direct);
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? command + ".exe" : command;
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, exe);
            if (Files.isExecutable(candidate)) {
                return true;
            }
            if (Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }

    /** @return the merged stdout ceiling, exposed for the health details */
    int maxStdoutBytes() {
        return maxStdoutBytes;
    }

    /** @return the result-file ceiling, exposed for the health details */
    long maxOutputBytes() {
        return maxOutputBytes;
    }

    /** @return the wall-clock deadline in force */
    Duration timeout() {
        return timeout;
    }
}
