package com.aistudy.server.ai.provider;

/**
 * AI-001 — provider-independent chat message.
 */
public record AiChatMessage(AiChatRole role, String content) {

    public static AiChatMessage system(String content) {
        return new AiChatMessage(AiChatRole.SYSTEM, content);
    }

    public static AiChatMessage user(String content) {
        return new AiChatMessage(AiChatRole.USER, content);
    }

    public static AiChatMessage assistant(String content) {
        return new AiChatMessage(AiChatRole.ASSISTANT, content);
    }
}
