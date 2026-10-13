package com.aistudy.server.ingestion.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-10 tests for the HTTP sidecar client against a real loopback server: the
 * status and body mapping is what decides whether an ingestion job reports a
 * diagnosable OCR code or hangs, and those branches cannot be exercised without
 * answering a request.
 *
 * <p>No PaddleOCR, no model, no database: the stub only speaks the JSON
 * contract documented on {@link HttpOcrEngine} and
 * {@code docs/operations-ocr-docker.md}.
 */
class HttpOcrEngineTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String body = "{\"text\":\"\",\"confidence\":1.0}";
    private volatile byte[] rawBody;
    private volatile String authorizationHeader;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            authorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            // Drain the request so the client never blocks on a half-read body.
            exchange.getRequestBody().readAllBytes();
            byte[] payload = rawBody != null ? rawBody : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpOcrEngine engine() {
        return engine(8L * 1024 * 1024);
    }

    private HttpOcrEngine engine(long maxResponseBytes) {
        return new HttpOcrEngine(new ObjectMapper(), baseUrl, "/ocr", "secret-token",
                Duration.ofSeconds(10), Duration.ofSeconds(2), maxResponseBytes,
                1024 * 1024, 200);
    }

    private File image() throws IOException {
        Path file = tempDir.resolve("page.png");
        Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4});
        return file.toFile();
    }

    @Test
    void happyPathReturnsTextAndConfidence() throws Exception {
        status = 200;
        body = "{\"text\":\"识别的文字\",\"confidence\":0.91,\"model\":\"ppocr-ch-cpu\",\"outcome\":\"OK\"}";

        OcrEngine.OcrResult result = engine().ocr(image());
        assertEquals("识别的文字", result.text());
        assertEquals(0.91, result.confidence());
        assertEquals(OcrOutcome.OK, result.outcome());
        assertEquals("ppocr-ch-cpu", result.model());
        assertEquals("Bearer secret-token", authorizationHeader);
    }

    @Test
    void emptyTextIsNoTextNotAFailure() throws Exception {
        status = 200;
        body = "{\"text\":\"   \",\"confidence\":0.5,\"outcome\":\"NO_TEXT\"}";

        OcrEngine.OcrResult result = engine().ocr(image());
        assertEquals("", result.text());
        assertEquals(OcrOutcome.NO_TEXT, result.outcome());
        assertTrue(result.needsReview());
    }

    @Test
    void missingConfidenceIsUnknownNeverOne() throws Exception {
        status = 200;
        body = "{\"text\":\"some words\"}";

        OcrEngine.OcrResult result = engine().ocr(image());
        assertTrue(Double.isNaN(result.confidence()));
        assertEquals(OcrOutcome.CONFIDENCE_UNKNOWN, result.outcome());
    }

    @Test
    void outOfRangeConfidenceIsTreatedAsUnknown() throws Exception {
        status = 200;
        body = "{\"text\":\"some words\",\"confidence\":87}";

        OcrEngine.OcrResult result = engine().ocr(image());
        assertTrue(Double.isNaN(result.confidence()));
        assertEquals(OcrOutcome.CONFIDENCE_UNKNOWN, result.outcome());
    }

    @Test
    void unknownOutcomeLabelDegradesToUnparseable() throws Exception {
        status = 200;
        body = "{\"text\":\"some words\",\"confidence\":0.9,\"outcome\":\"GREAT_SUCCESS\"}";

        // A label this build does not know is not a licence to treat the page
        // as clean: the text is kept, but the outcome says "we could not read
        // the verdict" and the source goes to review.
        OcrEngine.OcrResult result = engine().ocr(image());
        assertEquals(OcrOutcome.OUTPUT_UNPARSEABLE, result.outcome());
        assertTrue(result.needsReview());
    }

    @Test
    void nonJsonBodyIsUnparseable() throws Exception {
        status = 200;
        body = "PaddleOCR said: 识别的文字";

        OcrException error = assertThrows(OcrException.class, () -> engine().ocr(image()));
        assertEquals(OcrOutcome.OUTPUT_UNPARSEABLE, error.outcome());
    }

    @Test
    void unavailableSidecarStatusesMapToUnavailable() throws Exception {
        for (int code : new int[]{503, 500, 429}) {
            status = code;
            body = "{\"error\":\"LOADING\"}";
            OcrException error = assertThrows(OcrException.class, () -> engine().ocr(image()));
            assertEquals(OcrOutcome.UNAVAILABLE, error.outcome(), "for HTTP " + code);
        }
    }

    @Test
    void clientRejectionIsAProcessFailureNotSilentlyEmpty() throws Exception {
        status = 400;
        body = "{\"error\":\"BAD_REQUEST\",\"message\":\"image is not decodable\"}";

        OcrException error = assertThrows(OcrException.class, () -> engine().ocr(image()));
        assertEquals(OcrOutcome.PROCESS_FAILED, error.outcome());
        assertTrue(error.getMessage().contains("HTTP 400"), error.getMessage());
    }

    @Test
    void oversizedResponseFailsInsteadOfStreamingIntoMemory() throws Exception {
        status = 200;
        rawBody = new byte[64 * 1024];
        java.util.Arrays.fill(rawBody, (byte) 'a');

        OcrException error = assertThrows(OcrException.class, () -> engine(8192).ocr(image()));
        assertEquals(OcrOutcome.OUTPUT_TOO_LARGE, error.outcome());
    }

    @Test
    void oversizedImageIsRejectedBeforeAnyRequest() throws IOException {
        Path huge = tempDir.resolve("huge.png");
        Files.write(huge, new byte[2048]);
        HttpOcrEngine tiny = new HttpOcrEngine(new ObjectMapper(), baseUrl, "/ocr", null,
                Duration.ofSeconds(5), Duration.ofSeconds(1), 8192, 1024, 200);

        OcrException error = assertThrows(OcrException.class, () -> tiny.ocr(huge.toFile()));
        assertEquals(OcrOutcome.OUTPUT_TOO_LARGE, error.outcome());
        assertTrue(error.getMessage().contains("request size limit"), error.getMessage());
    }

    @Test
    void blankBaseUrlIsUnavailableNotANpe() {
        HttpOcrEngine unconfigured = new HttpOcrEngine(new ObjectMapper(), "", "/ocr", null,
                Duration.ofSeconds(5), Duration.ofSeconds(1), 8192, 8192, 200);
        assertFalse(unconfigured.available());
        OcrException error = assertThrows(OcrException.class, () -> unconfigured.ocr(new File("x.png")));
        assertEquals(OcrOutcome.UNAVAILABLE, error.outcome());
    }

    @Test
    void connectionRefusedIsUnavailable() throws IOException {
        server.stop(0);
        String dead = baseUrl;
        HttpOcrEngine engine = new HttpOcrEngine(new ObjectMapper(), dead, "/ocr", null,
                Duration.ofSeconds(5), Duration.ofSeconds(1), 8192, 8192, 200);

        OcrException error = assertThrows(OcrException.class, () -> engine.ocr(image()));
        assertEquals(OcrOutcome.UNAVAILABLE, error.outcome());
    }

    @Test
    void healthProbeFollowsTheSidecarStatus() throws Exception {
        HttpOcrEngine engine = engine();
        server.createContext("/healthz", exchange -> {
            byte[] payload = "{\"status\":\"loading\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        status = 503;
        assertFalse(engine.probe(Duration.ofSeconds(5)), "still loading is not ready");
        status = 200;
        assertTrue(engine.probe(Duration.ofSeconds(5)));
        assertNotNull(engine.endpoint());
        assertTrue(engine.endpoint().endsWith("/ocr"));
        assertEquals("PaddleOCR-HTTP", engine.name());
    }
}
