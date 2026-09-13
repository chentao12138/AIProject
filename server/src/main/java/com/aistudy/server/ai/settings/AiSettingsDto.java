package com.aistudy.server.ai.settings;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI-009 — public settings DTOs. GET never includes the API key.
 */
public final class AiSettingsDto {

    private AiSettingsDto() {
    }

    public record AiProviderSettingsView(
            boolean enabled,
            String provider,
            String preset,
            String baseUrl,
            String model,
            boolean apiKeyConfigured
    ) {
    }

    @Schema(description = "Write-only update. Omit apiKey to keep the stored secret.")
    public record UpdateAiProviderSettingsRequest(
            Boolean enabled,
            String provider,
            String preset,
            String baseUrl,
            String model,
            @Schema(writeOnly = true)
            String apiKey
    ) {
    }

    public record TestConnectionResponse(
            boolean success,
            String provider,
            String model,
            long latencyMs
    ) {
    }
}
