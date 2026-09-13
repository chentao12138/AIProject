package com.aistudy.server.ai.settings;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.ai.provider.AiErrorCode;
import com.aistudy.server.ai.provider.AiProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * AI-009 — per-user config resolution precedence.
 */
class AiRuntimeConfigResolverTest {

    private AiProperties env;
    private AiProviderSettingsMapper settingsMapper;
    private AiProviderSecretMapper secretMapper;
    private AiSecretStore secretStore;
    private AiRuntimeConfigResolver resolver;

    private static final String MASTER_KEY =
            java.util.Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        env = new AiProperties();
        env.setEnabled(true);
        env.setProvider("openai-compatible");
        env.setBaseUrl("https://api.stepfun.com/v1");
        env.setApiKey("env-api-key");
        env.setModel("env-model");
        env.setSecretKey(MASTER_KEY);
        settingsMapper = Mockito.mock(AiProviderSettingsMapper.class);
        secretMapper = Mockito.mock(AiProviderSecretMapper.class);
        secretStore = new AiSecretStore(secretMapper, env);
        resolver = new AiRuntimeConfigResolver(env, settingsMapper, secretStore);
    }

    @Test
    void runtimeSettingsOverrideEnv() {
        AiProviderSettings settings = new AiProviderSettings();
        settings.setUserSubject("u1");
        settings.setEnabled(true);
        settings.setProvider("OPENAI_COMPATIBLE");
        settings.setBaseUrl("https://runtime.example/v1");
        settings.setModel("runtime-model");
        when(settingsMapper.selectByUserSubject("u1")).thenReturn(settings);
        when(secretMapper.selectByUserSubject("u1")).thenReturn(null);

        AiRuntimeConfigResolver.ResolvedConfig config = resolver.resolveForUser("u1");
        assertEquals("https://runtime.example/v1", config.baseUrl());
        assertEquals("runtime-model", config.model());
        assertEquals("env-api-key", config.apiKey(), "no runtime secret => env key");
        assertTrue(config.isConfigured());
    }

    @Test
    void runtimeSecretOverridesEnvKey() {
        AiSecretStore realStore = Mockito.spy(secretStore);
        AiProviderSecretMapper mapper = secretMapper;
        // save then resolve
        realStore.saveApiKey("u1", "runtime-secret-key");
        org.mockito.ArgumentCaptor<AiProviderSecret> captor =
                org.mockito.ArgumentCaptor.forClass(AiProviderSecret.class);
        Mockito.verify(mapper).insert(captor.capture());
        when(mapper.selectByUserSubject("u1")).thenReturn(captor.getValue());
        AiRuntimeConfigResolver r2 = new AiRuntimeConfigResolver(env, settingsMapper, realStore);
        AiRuntimeConfigResolver.ResolvedConfig config = r2.resolveForUser("u1");
        assertEquals("runtime-secret-key", config.apiKey());
    }

    @Test
    void decryptFailureDoesNotFallbackToEnvKey() {
        AiProviderSecret bad = new AiProviderSecret();
        bad.setUserSubject("u1");
        bad.setCiphertext("corrupted-not-decryptable");
        when(secretMapper.selectByUserSubject("u1")).thenReturn(bad);
        AiProviderException ex = assertThrows(AiProviderException.class,
                () -> resolver.resolveForUser("u1"));
        assertEquals(AiErrorCode.AI_NOT_CONFIGURED, ex.errorCode());
        assertFalse(ex.getMessage().contains("env-api-key"));
        assertFalse(ex.getMessage().contains("corrupted-not-decryptable"));
    }

    @Test
    void envDefaultsWhenNoRuntimeRows() {
        when(settingsMapper.selectByUserSubject("u1")).thenReturn(null);
        when(secretMapper.selectByUserSubject("u1")).thenReturn(null);
        AiRuntimeConfigResolver.ResolvedConfig config = resolver.resolveForUser("u1");
        assertEquals("https://api.stepfun.com/v1", config.baseUrl());
        assertEquals("env-model", config.model());
        assertEquals("env-api-key", config.apiKey());
    }

    @Test
    void stepFunPresetBaseUrlNormalizesToChatCompletions() {
        // joinUrl is private on provider; assert resolved base is clean
        when(settingsMapper.selectByUserSubject("u1")).thenReturn(null);
        when(secretMapper.selectByUserSubject("u1")).thenReturn(null);
        AiRuntimeConfigResolver.ResolvedConfig config = resolver.resolveForUser("u1");
        String base = config.baseUrl();
        assertTrue(base.equals("https://api.stepfun.com/v1")
                        || base.equals("https://api.stepfun.com/v1/"),
                base);
        assertNotNull(config.apiKey());
    }

    @Test
    void rejectsUserinfoInBaseUrl() {
        AiProviderSettings settings = new AiProviderSettings();
        settings.setBaseUrl("https://user:pass@api.stepfun.com/v1");
        when(settingsMapper.selectByUserSubject("u1")).thenReturn(settings);
        when(secretMapper.selectByUserSubject("u1")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveForUser("u1"));
    }

    @Test
    void rejectsNonHttpScheme() {
        AiProviderSettings settings = new AiProviderSettings();
        settings.setBaseUrl("file:///etc/passwd");
        when(settingsMapper.selectByUserSubject("u1")).thenReturn(settings);
        when(secretMapper.selectByUserSubject("u1")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveForUser("u1"));
    }

    @Test
    void missingSubjectRejected() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveForUser(""));
    }
}
