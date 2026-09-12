package com.aistudy.server.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI-004 — public API DTOs. No vendor fields, no correctness material.
 */
public final class AiDto {

    private AiDto() {
    }

    public record CreateConversationRequest(String title) {
    }

    public record SendMessageRequest(String content) {
    }

    public record ConversationView(
            Long id,
            Long spaceId,
            String title,
            String status,
            LocalDateTime lastMessageAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record MessageView(
            Long id,
            Long conversationId,
            String role,
            String content,
            String provider,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            LocalDateTime createdAt
    ) {
    }

    public record ContextReference(
            String type,
            Long id,
            String title,
            String snippet
    ) {
    }

    public record SendMessageResponse(
            Long conversationId,
            MessageView userMessage,
            MessageView assistantMessage,
            List<ContextReference> contextReferences
    ) {
    }

    public record ConversationPageResponse(
            List<ConversationView> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }

    public record MessagePageResponse(
            List<MessageView> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }
}
