package com.aistudy.server.ai.provider;

/**
 * AI-001 — provider chat response. Never includes raw upstream JSON.
 */
public record AiChatResponse(
        String content,
        String provider,
        String model,
        AiUsage usage
) {
}
