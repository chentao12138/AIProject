package com.aistudy.server.ai.settings;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.ai.provider.AiErrorCode;
import com.aistudy.server.ai.provider.AiProviderException;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * AI-009 — per-user effective provider configuration.
 *
 * <p>Priority (deterministic, no silent secret merge):
 * <ol>
 *   <li>Non-secret: runtime user settings override env defaults.</li>
 *   <li>Secret: user encrypted secret; env {@code AISTUDY_AI_API_KEY} only
 *       when NO user secret row exists. Decrypt failure of an existing
 *       secret is a hard error (no env fallback).</li>
 * </ol>
 */
@Component
public class AiRuntimeConfigResolver {

    public record ResolvedConfig(
            boolean enabled,
            String provider,
            String baseUrl,
            String model,
            double temperature,
            int maxOutputTokens,
            Duration connectTimeout,
            Duration readTimeout,
            String apiKey
    ) {
        public boolean isConfigured() {
            return enabled
                    && baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank()
                    && apiKey != null && !apiKey.isBlank();
        }
    }

    private final AiProperties envProperties;
    private final AiProviderSettingsMapper settingsMapper;
    private final AiSecretStore secretStore;

    public AiRuntimeConfigResolver(AiProperties envProperties,
                                   AiProviderSettingsMapper settingsMapper,
                                   AiSecretStore secretStore) {
        this.envProperties = envProperties;
        this.settingsMapper = settingsMapper;
        this.secretStore = secretStore;
    }

    public ResolvedConfig resolveForUser(String userSubject) {
        if (userSubject == null || userSubject.isBlank()) {
            throw new IllegalArgumentException("userSubject is required");
        }
        AiProviderSettings settings = settingsMapper.selectByUserSubject(userSubject);

        boolean enabled = settings != null
                ? Boolean.TRUE.equals(settings.getEnabled())
                : envProperties.isEnabled();
        String provider = settings != null && settings.getProvider() != null
                ? settings.getProvider()
                : envProperties.getProvider();
        String baseUrl = settings != null && settings.getBaseUrl() != null && !settings.getBaseUrl().isBlank()
                ? settings.getBaseUrl()
                : envProperties.getBaseUrl();
        String model = settings != null && settings.getModel() != null && !settings.getModel().isBlank()
                ? settings.getModel()
                : envProperties.getModel();
        validateBaseUrl(baseUrl);

        String apiKey;
        boolean hasRuntimeSecret = secretStore.hasApiKey(userSubject);
        if (hasRuntimeSecret) {
            // Decrypt failure must NOT fall back to env key.
            try {
                apiKey = secretStore.resolveApiKey(userSubject);
            } catch (RuntimeException e) {
                throw new AiProviderException(AiErrorCode.AI_NOT_CONFIGURED,
                        "stored AI API key cannot be decrypted");
            }
        } else {
            apiKey = envProperties.getApiKey();
        }

        return new ResolvedConfig(
                enabled,
                provider,
                baseUrl,
                model,
                envProperties.getTemperature(),
                envProperties.getMaxOutputTokens(),
                envProperties.getConnectTimeout(),
                envProperties.getReadTimeout(),
                apiKey);
    }

    private static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        if (baseUrl.length() > 512) {
            throw new IllegalArgumentException("AI base URL is too long");
        }
        if (baseUrl.contains("@")) {
            throw new IllegalArgumentException("AI base URL must not embed credentials");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AI base URL is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("AI base URL scheme must be http or https");
        }
    }
}
