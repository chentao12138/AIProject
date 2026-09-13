package com.aistudy.server.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI-004 ~ AI-008 — public API DTOs. No vendor fields, no pre-submit
 * correctness material.
 */
public final class AiDto {

    private AiDto() {
    }

    public record CreateConversationRequest(String title) {
    }

    public record SendMessageRequest(String content) {
    }

    public record StudyCoachRequest(String question) {
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
            LocalDateTime createdAt,
            List<ContextReference> references
    ) {
    }

    public record ContextReference(
            String type,
            Long id,
            String title,
            String snippet,
            String locator
    ) {
        public ContextReference(String type, Long id, String title, String snippet) {
            this(type, id, title, snippet, null);
        }
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

    /** AI-007 — post-submit explanation (deterministic grade already decided). */
    public record ExplanationResponse(
            String explanation,
            List<String> keyConcepts,
            List<String> reviewSuggestions,
            List<Long> relatedKnowledgePointIds,
            List<ContextReference> contextReferences
    ) {
    }

    /** AI-008 — read-only study coach output. Never mutates learning state. */
    public record StudyCoachResponse(
            String summary,
            List<String> recommendedNextActions,
            List<Long> focusKnowledgePointIds,
            String rationale,
            List<ContextReference> contextReferences
    ) {
    }
}
