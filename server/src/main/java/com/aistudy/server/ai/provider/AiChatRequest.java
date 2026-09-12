package com.aistudy.server.ai.provider;

import java.util.List;

/**
 * AI-001 — provider chat request. System policy is always first and
 * constructed server-side; callers must never pass client SYSTEM messages.
 */
public record AiChatRequest(
        List<AiChatMessage> messages,
        Double temperature,
        Integer maxOutputTokens
) {
}
