package com.aistudy.server.ai.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI-001 — typed AI configuration unit contract.
 */
class AiPropertiesTest {

    @Test
    void defaultsAreSafeAndDisabled() {
        AiProperties properties = new AiProperties();
        assertFalse(properties.isEnabled());
        assertEquals("openai-compatible", properties.getProvider());
        assertEquals("", properties.getBaseUrl());
        assertEquals("", properties.getApiKey());
        assertEquals("", properties.getModel());
        assertEquals(0.3, properties.getTemperature(), 1e-9);
        assertEquals(1024, properties.getMaxOutputTokens());
        assertEquals(Duration.ofSeconds(5), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(60), properties.getReadTimeout());
        assertFalse(properties.isConfigured());
    }

    @Test
    void defaultContextLimitsAreBounded() {
        AiProperties.Context context = new AiProperties().getContext();
        assertEquals(8, context.getMaxSearchResults());
        assertEquals(12000, context.getMaxContextChars());
        assertEquals(8000, context.getMaxUserMessageChars());
        assertEquals(20, context.getMaxHistoryMessages());
    }

    @Test
    void blankSecretAndBaseUrlDoNotConfigureProvider() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        assertTrue(properties.isEnabled());
        assertFalse(properties.isConfigured(),
                "enabled alone must not make provider configured");
    }

    @Test
    void configuredRequiresEnabledBaseUrlKeyAndModel() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:9");
        properties.setApiKey("test-api-key");
        properties.setModel("test-model");
        assertTrue(properties.isConfigured());
    }

    @Test
    void configuredIsFalseWhenAnyRequiredFieldMissing() {
        AiProperties base = new AiProperties();
        base.setEnabled(true);
        base.setBaseUrl("http://127.0.0.1:9");
        base.setApiKey("test-api-key");
        base.setModel("test-model");

        AiProperties noKey = new AiProperties();
        noKey.setEnabled(true);
        noKey.setBaseUrl(base.getBaseUrl());
        noKey.setModel(base.getModel());
        assertFalse(noKey.isConfigured());

        AiProperties noModel = new AiProperties();
        noModel.setEnabled(true);
        noModel.setBaseUrl(base.getBaseUrl());
        noModel.setApiKey(base.getApiKey());
        assertFalse(noModel.isConfigured());

        AiProperties disabled = new AiProperties();
        disabled.setEnabled(false);
        disabled.setBaseUrl(base.getBaseUrl());
        disabled.setApiKey(base.getApiKey());
        disabled.setModel(base.getModel());
        assertFalse(disabled.isConfigured());
    }

    @Test
    void contextSettersRoundTrip() {
        AiProperties properties = new AiProperties();
        properties.getContext().setMaxSearchResults(4);
        properties.getContext().setMaxContextChars(6000);
        properties.getContext().setMaxUserMessageChars(1000);
        properties.getContext().setMaxHistoryMessages(5);
        assertEquals(4, properties.getContext().getMaxSearchResults());
        assertEquals(6000, properties.getContext().getMaxContextChars());
        assertEquals(1000, properties.getContext().getMaxUserMessageChars());
        assertEquals(5, properties.getContext().getMaxHistoryMessages());
    }

    @Test
    void temperatureAndTokenAndTimeoutRoundTrip() {
        AiProperties properties = new AiProperties();
        properties.setTemperature(0.7);
        properties.setMaxOutputTokens(256);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(10));
        assertEquals(0.7, properties.getTemperature(), 1e-9);
        assertEquals(256, properties.getMaxOutputTokens());
        assertEquals(Duration.ofSeconds(2), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(10), properties.getReadTimeout());
    }
}
