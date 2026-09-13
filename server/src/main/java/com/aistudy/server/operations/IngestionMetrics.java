package com.aistudy.server.operations;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * BUSINESS-025 — lightweight product metrics with bounded tags only.
 */
@Component
public class IngestionMetrics {

    private static final Set<String> TEXT_TYPES = Set.of("TXT", "MARKDOWN", "PDF", "IMAGE", "ZIP", "UNKNOWN");
    private static final java.util.Set<String> ERROR_CODES =
            java.util.Collections.unmodifiableSet(
                    java.util.Arrays.stream(com.aistudy.server.ingestion.job.service.IngestionErrorCode.values())
                            .map(Enum::name)
                            .collect(java.util.stream.Collectors.toSet())
            );

    private final MeterRegistry meterRegistry;

    public IngestionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordJob(String rawType) {
        String type = normalizeType(rawType);

        meterRegistry.counter(
                "aistudy.ingestion.jobs",
                "type", type
        ).increment();
    }

    public void recordFailure(String rawType, String rawErrorCode) {
        String type = normalizeType(rawType);
        String errorCode = normalizeErrorCode(rawErrorCode);

        meterRegistry.counter(
                "aistudy.ingestion.failures",
                "type", type,
                "error_code", errorCode
        ).increment();
    }

    private static String normalizeType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = rawType.trim().toUpperCase(java.util.Locale.ROOT);
        return TEXT_TYPES.contains(normalized) ? normalized : "UNKNOWN";
    }

    private static String normalizeErrorCode(String rawErrorCode) {
        if (rawErrorCode == null || rawErrorCode.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = rawErrorCode.trim().toUpperCase(java.util.Locale.ROOT);
        return ERROR_CODES.contains(normalized) ? normalized : "UNKNOWN";
    }
}
