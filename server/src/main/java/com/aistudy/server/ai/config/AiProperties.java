package com.aistudy.server.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI-001 — typed AI configuration under {@code aistudy.ai.*}.
 * Secrets never have real defaults; production injects via environment.
 */
@ConfigurationProperties(prefix = "aistudy.ai")
public class AiProperties {

    private boolean enabled = false;
    private String provider = "openai-compatible";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    private double temperature = 0.3;
    private int maxOutputTokens = 1024;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(60);
    private final Context context = new Context();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Context getContext() {
        return context;
    }

    public boolean isConfigured() {
        return enabled
                && baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }

    public static class Context {
        private int maxSearchResults = 8;
        private int maxContextChars = 12000;
        private int maxUserMessageChars = 8000;
        private int maxHistoryMessages = 20;

        public int getMaxSearchResults() {
            return maxSearchResults;
        }

        public void setMaxSearchResults(int maxSearchResults) {
            this.maxSearchResults = maxSearchResults;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }

        public int getMaxUserMessageChars() {
            return maxUserMessageChars;
        }

        public void setMaxUserMessageChars(int maxUserMessageChars) {
            this.maxUserMessageChars = maxUserMessageChars;
        }

        public int getMaxHistoryMessages() {
            return maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages;
        }
    }
}
