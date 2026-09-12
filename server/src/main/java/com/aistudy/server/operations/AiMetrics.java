package com.aistudy.server.operations;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * AI-001/004 — bounded AI metrics. Tags come only from closed sets.
 */
@Component
public class AiMetrics {

    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE");
    private static final Set<String> ERROR_CODES = Set.of(
            "AI_NOT_CONFIGURED",
            "AI_PROVIDER_TIMEOUT",
            "AI_PROVIDER_UNAVAILABLE",
            "AI_PROVIDER_REJECTED",
            "AI_PROVIDER_RESPONSE_INVALID",
            "UNKNOWN"
    );

    private final MeterRegistry meterRegistry;

    public AiMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(String provider, String outcome) {
        meterRegistry.counter(
                "aistudy.ai.requests",
                "provider", normalizeProvider(provider),
                "outcome", normalizeOutcome(outcome)
        ).increment();
    }

    public void recordFailure(String provider, String errorCode) {
        meterRegistry.counter(
                "aistudy.ai.failures",
                "provider", normalizeProvider(provider),
                "error_code", normalizeErrorCode(errorCode)
        ).increment();
    }

    private static String normalizeProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
    }

    private static String normalizeOutcome(String raw) {
        if (raw == null) {
            return "FAILURE";
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return OUTCOMES.contains(normalized) ? normalized : "FAILURE";
    }

    private static String normalizeErrorCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return ERROR_CODES.contains(normalized) ? normalized : "UNKNOWN";
    }
}
