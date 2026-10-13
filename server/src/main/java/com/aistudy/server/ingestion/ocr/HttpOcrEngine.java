package com.aistudy.server.ingestion.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OCR through an HTTP sidecar (T5 / F-10): the container image stays a plain
 * JRE build and PaddleOCR runs as its own service, which is the only shape
 * where the OCR runtime can get its own CPU / memory / model-volume limits and
 * its own health check.
 *
 * <h3>Wire protocol (documented contract with {@code deploy/ocr})</h3>
 *
 * <pre>
 *  POST ${aistudy.ocr.http.base-url}${aistudy.ocr.http.path}
 *  Content-Type: application/json
 *  Authorization: Bearer ${aistudy.ocr.http.token}   (omitted when unset)
 *  { "imageBase64": "&lt;base64 of the local image file&gt;", "filename": "…", "requestId": "…" }
 *
 *  200 { "text": "…", "confidence": 0.0–1.0, "model": "…", "outcome": "OK" }
 * </pre>
 *
 * <p>Same fences as the process engine: a request deadline
 * ({@code aistudy.ocr.timeout}), a response byte ceiling
 * ({@code aistudy.ocr.http.max-response-bytes}), and redacted bounded
 * diagnostics on failure. The image is read once, capped at
 * {@code aistudy.ocr.http.max-image-bytes}, so an oversized asset fails fast
 * instead of streaming into the heap.
 */
public class HttpOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(HttpOcrEngine.class);

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String path;
    private final String token;
    private final Duration timeout;
    private final long maxResponseBytes;
    private final long maxImageBytes;
    private final int maxDiagnosticsChars;

    public HttpOcrEngine(ObjectMapper objectMapper,
                         String baseUri,
                         String path,
                         String token,
                         Duration timeout,
                         Duration connectTimeout,
                         long maxResponseBytes,
                         long maxImageBytes,
                         int maxDiagnosticsChars) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUri == null ? "" : baseUri.trim();
        this.path = path == null || path.isBlank() ? "/ocr" : path;
        this.token = token;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofMinutes(2) : timeout;
        this.maxResponseBytes = Math.max(1024L, maxResponseBytes);
        this.maxImageBytes = Math.max(1024L, maxImageBytes);
        this.maxDiagnosticsChars = Math.max(64, maxDiagnosticsChars);
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null || connectTimeout.isZero()
                        || connectTimeout.isNegative() ? Duration.ofSeconds(5) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Failure modes: {@code UNAVAILABLE} (no base-url, connection refused,
     * 4xx/5xx), {@code TIMEOUT} (connect or request deadline),
     * {@code OUTPUT_TOO_LARGE} (image or response over the ceiling),
     * {@code OUTPUT_UNPARSEABLE} (non-JSON body).
     */
    @Override
    public OcrResult ocr(File imageFile) {
        if (baseUrl.isBlank()) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR sidecar base URL is not configured", null, null);
        }
        byte[] imageBytes = readCapped(imageFile);
        String requestJson;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("imageBase64", Base64.getEncoder().encodeToString(imageBytes));
            payload.put("filename", imageFile.getName());
            requestJson = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new OcrException(OcrOutcome.PROCESS_FAILED,
                    "OCR request could not be serialised", null, e);
        }

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR sidecar base URL is not a valid URI", null, e);
        }
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }

        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new OcrException(OcrOutcome.TIMEOUT,
                    "OCR sidecar did not answer within " + timeout.toSeconds() + "s", null, e);
        } catch (IOException e) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR sidecar is not reachable", OcrDiagnostics.redact(
                            e.getMessage(), maxDiagnosticsChars, null), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrOutcome.PROCESS_FAILED,
                    "OCR request was interrupted", null, e);
        }

        try (InputStream in = response.body()) {
            String body = readBodyCapped(in);
            int status = response.statusCode();
            if (status >= 500 || status == 429) {
                throw new OcrException(OcrOutcome.UNAVAILABLE,
                        "OCR sidecar returned HTTP " + status,
                        OcrDiagnostics.redact(body, maxDiagnosticsChars, null), null);
            }
            if (status < 200 || status >= 300) {
                throw new OcrException(OcrOutcome.PROCESS_FAILED,
                        "OCR sidecar rejected the request with HTTP " + status,
                        OcrDiagnostics.redact(body, maxDiagnosticsChars, null), null);
            }
            return parse(body);
        } catch (IOException e) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR sidecar response could not be read", null, e);
        }
    }

    /**
     * @param body sidecar JSON response
     * @return the mapped result; an unknown {@code outcome} label degrades to
     *         {@link OcrOutcome#OUTPUT_UNPARSEABLE} rather than OK
     */
    private OcrResult parse(String body) {
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new OcrException(OcrOutcome.OUTPUT_UNPARSEABLE,
                    "OCR sidecar response is not JSON",
                    OcrDiagnostics.redact(body, maxDiagnosticsChars, null), e);
        }
        if (node == null || !node.isObject()) {
            throw new OcrException(OcrOutcome.OUTPUT_UNPARSEABLE,
                    "OCR sidecar response has no result object",
                    OcrDiagnostics.redact(body, maxDiagnosticsChars, null), null);
        }
        String text = node.path("text").asText("").trim();
        double confidence = node.path("confidence").isNumber()
                ? node.path("confidence").asDouble() : OcrEngine.CONFIDENCE_UNKNOWN;
        if (Double.isFinite(confidence) && (confidence < 0.0 || confidence > 1.0)) {
            confidence = OcrEngine.CONFIDENCE_UNKNOWN;
        }
        String model = node.path("model").isTextual() ? node.path("model").asText() : null;
        OcrOutcome outcome;
        if (node.path("outcome").isTextual()) {
            outcome = OcrOutcome.fromLabel(node.path("outcome").asText());
        } else if (text.isBlank()) {
            outcome = OcrOutcome.NO_TEXT;
        } else if (Double.isNaN(confidence)) {
            outcome = OcrOutcome.CONFIDENCE_UNKNOWN;
        } else {
            outcome = OcrOutcome.OK;
        }
        return new OcrResult(text, confidence, "PaddleOCR-HTTP", model, outcome);
    }

    /** @return the local image bytes, capped so an asset cannot balloon the request */
    private byte[] readCapped(File imageFile) {
        long size = imageFile.length();
        if (size > maxImageBytes) {
            throw new OcrException(OcrOutcome.OUTPUT_TOO_LARGE,
                    "image exceeds the OCR request size limit of " + maxImageBytes + " bytes", null, null);
        }
        try {
            return java.nio.file.Files.readAllBytes(imageFile.toPath());
        } catch (IOException e) {
            throw new OcrException(OcrOutcome.UNAVAILABLE,
                    "OCR input image could not be read", null, e);
        }
    }

    /**
     * @param in response stream
     * @return body text, never longer than {@code maxResponseBytes}
     * @throws OcrException {@code OUTPUT_TOO_LARGE} past the ceiling
     */
    private String readBodyCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min((int) Math.min(maxResponseBytes, 1 << 16),
                1 << 16));
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxResponseBytes) {
                throw new OcrException(OcrOutcome.OUTPUT_TOO_LARGE,
                        "OCR sidecar response exceeds the configured limit of "
                                + maxResponseBytes + " bytes", null, null);
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // the response is already consumed or abandoned
        }
    }

    /** @return true only when a base URL is configured; the caller then knows OCR is real */
    @Override
    public boolean available() {
        return !baseUrl.isBlank();
    }

    @Override
    public String name() {
        return "PaddleOCR-HTTP";
    }

    /** @return the sidecar endpoint for health details (never null) */
    public String endpoint() {
        return baseUrl.isBlank() ? null : baseUrl + path;
    }

    /**
     * Cheap readiness probe: a {@code GET {base-url}/healthz} against the
     * sidecar with a short deadline, used by {@link OcrHealthIndicator}.
     *
     * @param probeTimeout deadline for the probe only, never the OCR deadline
     * @return true when the sidecar answered 2xx; false on any error, which the
     *         caller reports as degraded without failing the request path
     */
    public boolean probe(Duration probeTimeout) {
        if (baseUrl.isBlank()) {
            return false;
        }
        try {
            HttpRequest.Builder probeBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/healthz"))
                    .timeout(probeTimeout == null ? Duration.ofSeconds(2) : probeTimeout)
                    .GET();
            if (token != null && !token.isBlank()) {
                probeBuilder.header("Authorization", "Bearer " + token.trim());
            }
            HttpResponse<InputStream> response =
                    client.send(probeBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            closeQuietly(response.body());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.debug("OCR sidecar probe failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
