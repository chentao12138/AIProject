package com.aistudy.server.ai.service;

import com.aistudy.server.ai.context.AiContextItem;
import com.aistudy.server.ai.entity.AiConversation;
import com.aistudy.server.ai.entity.AiMessage;
import com.aistudy.server.ai.entity.AiMessageReference;
import com.aistudy.server.ai.mapper.AiConversationMapper;
import com.aistudy.server.ai.mapper.AiMessageMapper;
import com.aistudy.server.ai.mapper.AiMessageReferenceMapper;
import com.aistudy.server.ai.provider.AiChatResponse;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI-002 / AI-005 — short-transaction persistence for AI messages and
 * their grounded references. Provider HTTP calls remain OUTSIDE these
 * transactions.
 */
@Service
public class AiMessagePersistenceService {

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiMessageReferenceMapper messageReferenceMapper;

    public AiMessagePersistenceService(AiConversationMapper conversationMapper,
                                       AiMessageMapper messageMapper,
                                       AiMessageReferenceMapper messageReferenceMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.messageReferenceMapper = messageReferenceMapper;
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

    /**
     * Atomically persists the ASSISTANT message and the exact bounded
     * references that were actually sent to the provider.
     */
    @Transactional
    public AiMessage persistAssistantMessage(String ownerSubject,
                                             Long spaceId,
                                             AiConversation conversation,
                                             AiChatResponse response,
                                             List<AiContextItem> contextItems) {
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

        if (contextItems != null) {
            int ordinal = 0;
            for (AiContextItem item : contextItems) {
                if (item == null || item.type() == null) {
                    continue;
                }
                AiMessageReference reference = new AiMessageReference();
                reference.setMessageId(message.getId());
                reference.setOrdinal(ordinal++);
                reference.setReferenceType(item.type());
                reference.setEntityId(item.entityId());
                reference.setTitle(truncate(item.title(), 512));
                reference.setSnippet(truncate(item.snippet(), 2000));
                reference.setLocator(null);
                reference.setCreatedAt(now);
                messageReferenceMapper.insert(reference);
            }
        }
        return message;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
