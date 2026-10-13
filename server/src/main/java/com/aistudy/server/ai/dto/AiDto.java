package com.aistudy.server.ai.dto;

import com.aistudy.server.ai.context.AiContextItem;
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

    /**
     * C03 — the context IDs are optional pointers into the LearningSpace that
     * owns the conversation. The client sends IDs only, never body text: the
     * server resolves the material and assembles the prompt.
     */
    public record SendMessageRequest(
            String content,
            Long sourceId,
            Long sourcePageId,
            Long contentBlockId,
            Long knowledgePointId
    ) {

        public SendMessageRequest(String content) {
            this(content, null, null, null, null);
        }
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
            String groundingMode,
            LocalDateTime createdAt,
            List<ContextReference> references
    ) {
    }

    /**
     * C04 — a citation the client can route on. {@code locator} is only the
     * human-readable label; the ID fields say which level the pointer lives at,
     * so a CONTENT_BLOCK reference deep-links
     * {@code /spaces/{spaceId}/sources/{sourceId}?page={sourcePageId}&block={contentBlockId}}.
     * For a SOURCE / SOURCE_PAGE / CONTENT_BLOCK reference {@code id} is the
     * entity of that type, and every owning ID above it is filled too.
     */
    public record ContextReference(
            String type,
            Long id,
            String title,
            String snippet,
            String locator,
            Long sourceId,
            Long sourcePageId,
            Long contentBlockId
    ) {

        public static ContextReference from(AiContextItem item) {
            if (item == null) {
                return null;
            }
            return new ContextReference(
                    item.type(), item.entityId(), item.title(), item.snippet(),
                    item.locator(), item.sourceId(), item.sourcePageId(), item.contentBlockId());
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
