package com.aistudy.server.ai.provider;

/**
 * AI-001 — optional upstream token usage. Never fabricated locally.
 */
public record AiUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    public static AiUsage empty() {
        return new AiUsage(null, null, null);
    }
}
