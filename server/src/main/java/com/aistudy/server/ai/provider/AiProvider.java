package com.aistudy.server.ai.provider;

/**
 * AI-001 — provider-independent chat port.
 * Orchestration services depend on this interface, never a vendor SDK.
 */
public interface AiProvider {

    AiChatResponse chat(AiChatRequest request);
}
