package com.aistudy.server.ai.settings;

import com.aistudy.server.ai.settings.AiSettingsDto.AiProviderSettingsView;
import com.aistudy.server.ai.settings.AiSettingsDto.UpdateAiProviderSettingsRequest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI-009 — per-user runtime provider settings. {@code userSubject} is always
 * the authenticated JWT subject supplied by the controller, never request JSON.
 */
@Service
public class AiProviderSettingsService {

    private final AiProviderSettingsMapper settingsMapper;
    private final AiSecretStore secretStore;

    public AiProviderSettingsService(AiProviderSettingsMapper settingsMapper,
                                     AiSecretStore secretStore) {
        this.settingsMapper = settingsMapper;
        this.secretStore = secretStore;
    }

    public AiProviderSettingsView getSettings(String userSubject) {
        AiProviderSettings settings = settingsMapper.selectByUserSubject(userSubject);
        if (settings == null) {
            return new AiProviderSettingsView(
                    false,
                    AiProviderIds.OPENAI_COMPATIBLE,
                    null,
                    null,
                    null,
                    secretStore.hasApiKey(userSubject));
        }
        return new AiProviderSettingsView(
                Boolean.TRUE.equals(settings.getEnabled()),
                settings.getProvider(),
                settings.getPreset(),
                settings.getBaseUrl(),
                settings.getModel(),
                secretStore.hasApiKey(userSubject));
    }

    @Transactional
    public AiProviderSettingsView updateSettings(String userSubject,
                                                 UpdateAiProviderSettingsRequest request) {
        String provider = AiProviderIds.normalizeProvider(request.provider());
        String preset = AiProviderIds.normalizePreset(request.preset());
        String baseUrl = request.baseUrl();
        String model = request.model();

        if (AiProviderIds.PRESET_STEPMUN.equals(preset)) {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = AiProviderIds.PRESET_STEPMUN_BASE_URL;
            }
            if (model == null || model.isBlank()) {
                model = AiProviderIds.PRESET_STEPMUN_MODEL;
            }
        }

        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            secretStore.saveApiKey(userSubject, request.apiKey());
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        AiProviderSettings settings = settingsMapper.selectByUserSubject(userSubject);
        if (settings == null) {
            settings = new AiProviderSettings();
            settings.setUserSubject(userSubject);
            settings.setEnabled(Boolean.TRUE.equals(request.enabled()));
            settings.setProvider(provider);
            settings.setPreset(preset);
            settings.setBaseUrl(baseUrl);
            settings.setModel(model);
            settings.setCreatedAt(now);
            settings.setUpdatedAt(now);
            settingsMapper.insert(settings);
        } else {
            if (request.enabled() != null) {
                settings.setEnabled(request.enabled());
            }
            settings.setProvider(provider);
            settings.setPreset(preset);
            settings.setBaseUrl(baseUrl);
            settings.setModel(model);
            settings.setUpdatedAt(now);
            settingsMapper.updateById(settings);
        }
        return getSettings(userSubject);
    }

    @Transactional
    public void deleteApiKey(String userSubject) {
        secretStore.deleteApiKey(userSubject);
    }
}
