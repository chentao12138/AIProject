package com.aistudy.server.ai.service;

import com.aistudy.server.ai.entity.AiConversation;
import com.aistudy.server.ai.entity.AiMessage;
import com.aistudy.server.ai.mapper.AiConversationMapper;
import com.aistudy.server.ai.mapper.AiMessageMapper;
import com.aistudy.server.ai.provider.AiChatResponse;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI-002 — short-transaction persistence for AI messages.
 *
 * <p>Each public method atomically inserts one {@code ai_message} row and
 * updates {@code ai_conversation.last_message_at}. These methods live on a
 * separate bean so Spring's transaction proxy applies without same-bean
 * self-invocation. The caller MUST NOT wrap the provider HTTP call inside
 * these transactions.
 */
@Service
public class AiMessagePersistenceService {

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    public AiMessagePersistenceService(AiConversationMapper conversationMapper,
                                       AiMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public AiMessage persistUserMessage(String ownerSubject,
                                        Long spaceId,
                                        AiConversation conversation,
                                        String content) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        AiMessage message = new AiMessage();
        message.setConversationId(conversation.getId());
        message.setRole(AiConversationService.ROLE_USER);
        message.setContent(content);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        conversationMapper.touchLastMessageAt(
                conversation.getId(), spaceId, ownerSubject, now);
        return message;
    }

    @Transactional
    public AiMessage persistAssistantMessage(String ownerSubject,
                                             Long spaceId,
                                             AiConversation conversation,
                                             AiChatResponse response) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        AiMessage message = new AiMessage();
        message.setConversationId(conversation.getId());
        message.setRole(AiConversationService.ROLE_ASSISTANT);
        message.setContent(response.content() == null ? "" : response.content());
        message.setProvider(response.provider());
        message.setModel(response.model());
        if (response.usage() != null) {
            message.setPromptTokens(response.usage().promptTokens());
            message.setCompletionTokens(response.usage().completionTokens());
            message.setTotalTokens(response.usage().totalTokens());
        }
        message.setCreatedAt(now);
        messageMapper.insert(message);
        conversationMapper.touchLastMessageAt(
                conversation.getId(), spaceId, ownerSubject, now);
        return message;
    }
}
