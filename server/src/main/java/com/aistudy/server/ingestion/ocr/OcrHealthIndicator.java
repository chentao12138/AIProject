package com.aistudy.server.ingestion.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Reports the OCR stage as its own health component ({@code /actuator/health}
 * detail id {@code ocr}) without ever making the application itself look dead
 * (F-10).
 *
 * <p>Why the non-strict default matters: OCR is optional, and a container
 * orchestrator that restarts the backend because the OCR sidecar is still
 * loading a 200 MB model turns a slow dependency into a crash loop. So the
 * indicator reports the custom status {@code DEGRADED}, which Spring Boot's
 * status aggregator ranks below {@code DOWN} (it can never become the composite
 * status), and the operator opts into hard readiness gating with
 * {@code aistudy.ocr.health-strict=true} when they really want the pod to be
 * taken out of rotation.
 *
 * <p>Probing is bounded: the HTTP engine probe uses its own short
 * {@code aistudy.ocr.health-probe-timeout} instead of the OCR run deadline, so
 * a health scrape can never hold an ingestion worker's lock or take 2 minutes.
 */
@Component("ocr")
public class OcrHealthIndicator implements HealthIndicator {

    /** Custom status for "OCR configured but not usable"; never escalates. */
    private static final String STATUS_DEGRADED = "DEGRADED";

    private final OcrEngine engine;
    private final boolean enabled;
    private final boolean strict;
    private final Duration probeTimeout;

    public OcrHealthIndicator(OcrEngine engine,
                              @Value("${aistudy.ocr.enabled:false}") boolean enabled,
                              @Value("${aistudy.ocr.health-strict:false}") boolean strict,
                              @Value("${aistudy.ocr.health-probe-timeout:PT2S}") Duration probeTimeout) {
        this.engine = engine;
        this.enabled = enabled;
        this.strict = strict;
        this.probeTimeout = probeTimeout == null || probeTimeout.isZero() || probeTimeout.isNegative()
                ? Duration.ofSeconds(2) : probeTimeout;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up()
                    .withDetail("engine", "none")
                    .withDetail("mode", "text-layer-only")
                    .withDetail("note", "OCR is not configured; image assets ingest without text")
                    .build();
        }
        boolean usable;
        try {
            usable = probe();
        } catch (RuntimeException e) {
            // A health check must never be the thing that breaks a request.
            usable = false;
        }
        if (usable) {
            return Health.up()
                    .withDetail("engine", engine.name())
                    .withDetail("endpoint", endpoint())
                    .build();
        }
        Health.Builder builder = strict ? Health.down() : Health.status(STATUS_DEGRADED);
        return builder
                .withDetail("engine", engine.name())
                .withDetail("endpoint", endpoint())
                .withDetail("reason", "OCR engine is configured but not reachable or not installed")
                .withDetail("action", "start the ocr service (deploy/ocr) or set aistudy.ocr.enabled=false")
                .build();
    }

    /**
     * @return true when the engine can actually serve a request now: the HTTP
     *         engine gets a bounded {@code /healthz} probe, the process engine
     *         an executable-resolvability check that does not launch a child
     */
    private boolean probe() {
        if (engine instanceof HttpOcrEngine http) {
            return http.available() && http.probe(probeTimeout);
        }
        return engine.available();
    }

    /** @return the remote endpoint for HTTP, or {@code local-process} / {@code none} */
    private String endpoint() {
        if (engine instanceof HttpOcrEngine http) {
            String url = http.endpoint();
            return url == null ? "not-configured" : url;
        }
        return engine.available() ? "local-process" : "local-process-missing";
    }
}
