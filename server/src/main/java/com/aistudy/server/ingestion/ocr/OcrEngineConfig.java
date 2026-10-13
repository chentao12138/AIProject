package com.aistudy.server.ingestion.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Single place where the OCR engine is chosen, so the extraction services never
 * have to know which deployment shape they run in (F-10).
 *
 * <p>Selection: {@code aistudy.ocr.enabled=false} (the default) or
 * {@code aistudy.ocr.engine=none} &rarr; {@link DisabledOcrEngine};
 * {@code engine=process} &rarr; {@link PaddleOcrProcessEngine} (local
 * executable, the shape used by a developer machine or an all-in-one image);
 * {@code engine=http} &rarr; {@link HttpOcrEngine} (the
 * {@code deploy/ocr} sidecar, the shape used by Docker).
 *
 * <p>Exactly one bean is always produced — {@code NoSuchBeanDefinition} or
 * {@code NoUniqueBeanDefinition} at startup would take the whole API down for a
 * feature that is optional, which is the opposite of the failure behaviour
 * this task is meant to specify.
 */
@Configuration
public class OcrEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(OcrEngineConfig.class);

    @Bean
    public OcrEngine ocrEngine(
            ObjectMapper objectMapper,
            @Value("${aistudy.ocr.enabled:false}") boolean enabled,
            @Value("${aistudy.ocr.engine:none}") String engine,
            @Value("${aistudy.ocr.paddle-ocr-command:paddleocr}") String paddleOcrCommand,
            @Value("${aistudy.ocr.temp-dir:${java.io.tmpdir}/aistudy/ocr}") String ocrTempDir,
            @Value("${aistudy.ocr.timeout:PT2M}") Duration timeout,
            @Value("${aistudy.ocr.kill-grace:PT5S}") Duration killGrace,
            @Value("${aistudy.ocr.max-stdout-bytes:262144}") int maxStdoutBytes,
            @Value("${aistudy.ocr.max-output-bytes:8MB}") DataSize maxOutputBytes,
            @Value("${aistudy.ocr.max-diagnostics-chars:2000}") int maxDiagnosticsChars,
            @Value("${aistudy.ocr.http.base-url:}") String httpBaseUrl,
            @Value("${aistudy.ocr.http.path:/ocr}") String httpPath,
            @Value("${aistudy.ocr.http.token:}") String httpToken,
            @Value("${aistudy.ocr.http.connect-timeout:PT5S}") Duration httpConnectTimeout,
            @Value("${aistudy.ocr.http.max-response-bytes:8MB}") DataSize httpMaxResponseBytes,
            @Value("${aistudy.ocr.http.max-image-bytes:20MB}") DataSize httpMaxImageBytes) {
        String choice = engine == null ? "none" : engine.trim().toLowerCase();
        if (!enabled || choice.isBlank() || "none".equals(choice)) {
            log.info("OCR disabled: extraction runs text-layer-only, image assets get no text");
            return new DisabledOcrEngine();
        }
        if ("http".equals(choice)) {
            log.info("OCR engine: http sidecar {}", httpBaseUrl);
            return new HttpOcrEngine(objectMapper, httpBaseUrl, httpPath, httpToken, timeout,
                    httpConnectTimeout, httpMaxResponseBytes.toBytes(), httpMaxImageBytes.toBytes(),
                    maxDiagnosticsChars);
        }
        if (!"process".equals(choice)) {
            log.warn("Unknown aistudy.ocr.engine '{}', falling back to the local process engine", engine);
        }
        return new PaddleOcrProcessEngine(paddleOcrCommand, ocrTempDir, timeout, killGrace,
                maxStdoutBytes, maxOutputBytes, maxDiagnosticsChars);
    }

    /**
     * Failure policy for the OCR stage (F-28).
     *
     * @param raw {@code review} (default) or {@code fail}
     * @return the policy; anything unrecognised resolves to {@code review} so a
     *         typo cannot turn every image ingestion into a hard failure
     */
    static OcrFailurePolicy parseFailurePolicy(String raw) {
        if (raw == null) {
            return OcrFailurePolicy.REVIEW;
        }
        String value = raw.trim().toLowerCase();
        if (value.startsWith("f")) {
            return OcrFailurePolicy.FAIL;
        }
        return OcrFailurePolicy.REVIEW;
    }

    /** @return the failure policy resolved from configuration */
    @Bean
    public OcrFailurePolicy ocrFailurePolicy(@Value("${aistudy.ocr.on-failure:review}") String onFailure) {
        return parseFailurePolicy(onFailure);
    }
}
