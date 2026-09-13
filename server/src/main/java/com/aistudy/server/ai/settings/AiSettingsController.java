package com.aistudy.server.ai.settings;

import com.aistudy.server.ai.settings.AiSettingsDto.AiProviderSettingsView;
import com.aistudy.server.ai.settings.AiSettingsDto.TestConnectionResponse;
import com.aistudy.server.ai.settings.AiSettingsDto.UpdateAiProviderSettingsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI-009 — per-user runtime AI provider settings. API key is write-only.
 * Subject always comes from the authenticated principal, never request JSON.
 */
@RestController
@RequestMapping(value = "/api/v1/settings/ai", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
public class AiSettingsController {

    private final AiProviderSettingsService settingsService;
    private final AiProviderConnectionTestService connectionTestService;

    public AiSettingsController(AiProviderSettingsService settingsService,
                                AiProviderConnectionTestService connectionTestService) {
        this.settingsService = settingsService;
        this.connectionTestService = connectionTestService;
    }

    @Operation(summary = "Get my AI provider settings (never returns the API key)")
    @GetMapping
    public AiProviderSettingsView getSettings(Authentication authentication) {
        return settingsService.getSettings(authentication.getName());
    }

    @Operation(summary = "Update my AI provider settings; omit apiKey to keep the stored secret")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public AiProviderSettingsView updateSettings(
            @Valid @RequestBody UpdateAiProviderSettingsRequest request,
            Authentication authentication) {
        return settingsService.updateSettings(authentication.getName(), request);
    }

    @Operation(summary = "Test my provider connectivity from the backend")
    @PostMapping("/test-connection")
    public TestConnectionResponse testConnection(Authentication authentication) {
        return connectionTestService.testConnection(authentication.getName());
    }

    @Operation(summary = "Delete my stored AI API key")
    @DeleteMapping("/api-key")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApiKey(Authentication authentication) {
        settingsService.deleteApiKey(authentication.getName());
    }
}
