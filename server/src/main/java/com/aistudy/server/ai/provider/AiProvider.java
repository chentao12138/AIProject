package com.aistudy.server.ai.provider;

/**
 * AI-001 / AI-009 — provider-independent chat port.
 * Orchestration depends on this interface, never a vendor SDK.
 * {@code userSubject} selects the caller's runtime provider configuration.
 */
public interface AiProvider {

    AiChatResponse chat(AiChatRequest request, String userSubject);
}
