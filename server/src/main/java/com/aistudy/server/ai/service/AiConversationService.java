package com.aistudy.server.ai.service;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.ai.context.AiContextItem;
import com.aistudy.server.ai.context.AiLearningContextService;
import com.aistudy.server.ai.dto.AiDto.ConversationPageResponse;
import com.aistudy.server.ai.dto.AiDto.ConversationView;
import com.aistudy.server.ai.dto.AiDto.ContextReference;
import com.aistudy.server.ai.dto.AiDto.MessagePageResponse;
import com.aistudy.server.ai.dto.AiDto.MessageView;
import com.aistudy.server.ai.entity.AiConversation;
import com.aistudy.server.ai.entity.AiMessage;
import com.aistudy.server.ai.entity.AiMessageReference;
import com.aistudy.server.ai.learning.AiLearningState;
import com.aistudy.server.ai.learning.AiLearningStateService;
import com.aistudy.server.ai.mapper.AiConversationMapper;
import com.aistudy.server.ai.mapper.AiMessageMapper;
import com.aistudy.server.ai.mapper.AiMessageReferenceMapper;
import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiErrorCode;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.provider.AiProviderException;
import com.aistudy.server.ai.provider.AiChatRole;
import com.aistudy.server.ai.prompt.AiTutorPromptBuilder;
import com.aistudy.server.ai.settings.AiRuntimeConfigResolver;
import com.aistudy.server.operations.AiMetrics;
import com.aistudy.server.space.service.LearningSpaceService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI-002 / AI-004 — conversation orchestration and tutor chat.
 *
 * <p>Message persistence uses {@link AiMessagePersistenceService} short
 * transactions. Network calls to {@link AiProvider} happen OUTSIDE any DB
 * transaction and are never same-bean {@code @Transactional} self-invocations.
 */
@Service
public class AiConversationService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    private static final Logger log = LoggerFactory.getLogger(AiConversationService.class);
    private static final int DEFAULT_CONVERSATION_SIZE = 20;
    private static final int DEFAULT_MESSAGE_SIZE = 50;
    private static final int TITLE_MAX = 80;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiMessageReferenceMapper messageReferenceMapper;
    private final AiMessagePersistenceService messagePersistenceService;
    private final LearningSpaceService learningSpaceService;
    private final AiLearningContextService learningContextService;
    private final AiLearningStateService learningStateService;
    private final AiProvider aiProvider;
    private final AiProperties aiProperties;
    private final AiRuntimeConfigResolver configResolver;
    private final AiMetrics aiMetrics;

    public AiConversationService(AiConversationMapper conversationMapper,
                                 AiMessageMapper messageMapper,
                                 AiMessageReferenceMapper messageReferenceMapper,
                                 AiMessagePersistenceService messagePersistenceService,
                                 LearningSpaceService learningSpaceService,
                                 AiLearningContextService learningContextService,
                                 AiLearningStateService learningStateService,
                                 AiProvider aiProvider,
                                 AiProperties aiProperties,
                                 AiRuntimeConfigResolver configResolver,
                                 AiMetrics aiMetrics) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.messageReferenceMapper = messageReferenceMapper;
        this.messagePersistenceService = messagePersistenceService;
        this.learningSpaceService = learningSpaceService;
        this.learningContextService = learningContextService;
        this.learningStateService = learningStateService;
        this.aiProvider = aiProvider;
        this.aiProperties = aiProperties;
        this.configResolver = configResolver;
        this.aiMetrics = aiMetrics;
    }

    @Transactional
    public ConversationView createConversation(String ownerSubject, Long spaceId, String title) {
        requireSpace(ownerSubject, spaceId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        AiConversation conversation = new AiConversation();
        conversation.setSpaceId(spaceId);
        conversation.setUserSubject(ownerSubject);
        conversation.setTitle(deriveTitle(title, null));
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return toView(conversation);
    }

    public ConversationPageResponse listConversations(String ownerSubject,
                                                      Long spaceId,
                                                      int page,
                                                      int size) {
        requireSpace(ownerSubject, spaceId);
        int safePage = Math.max(0, page);
        int safeSize = clampSize(size, DEFAULT_CONVERSATION_SIZE);
        int offset = safePage * safeSize;
        List<AiConversation> rows = conversationMapper.selectPageBySpaceUser(
                spaceId, ownerSubject, STATUS_ACTIVE, offset, safeSize);
        long total = conversationMapper.countBySpaceUserStatus(
                spaceId, ownerSubject, STATUS_ACTIVE);
        int totalPages = (int) Math.max(1, Math.ceil((double) total / safeSize));
        List<ConversationView> content = new ArrayList<>(rows.size());
        for (AiConversation row : rows) {
            content.add(toView(row));
        }
        return new ConversationPageResponse(
                Collections.unmodifiableList(content), safePage, safeSize, total, totalPages);
    }

    public ConversationView getConversation(String ownerSubject, Long spaceId, Long conversationId) {
        requireSpace(ownerSubject, spaceId);
        AiConversation conversation = conversationMapper.selectByIdSpaceUser(
                conversationId, spaceId, ownerSubject);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return toView(conversation);
    }

    @Transactional
    public ConversationView archiveConversation(String ownerSubject,
                                                Long spaceId,
                                                Long conversationId) {
        requireSpace(ownerSubject, spaceId);
        AiConversation conversation = conversationMapper.selectByIdSpaceUser(
                conversationId, spaceId, ownerSubject);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        conversationMapper.updateStatus(conversationId, spaceId, ownerSubject, STATUS_ARCHIVED, now);
        conversation.setStatus(STATUS_ARCHIVED);
        conversation.setUpdatedAt(now);
        return toView(conversation);
    }

    public MessagePageResponse listMessages(String ownerSubject,
                                            Long spaceId,
                                            Long conversationId,
                                            int page,
                                            int size) {
        requireSpace(ownerSubject, spaceId);
        AiConversation conversation = requireConversation(ownerSubject, spaceId, conversationId);
        int safePage = Math.max(0, page);
        int safeSize = clampSize(size, DEFAULT_MESSAGE_SIZE);
        int offset = safePage * safeSize;
        List<AiMessage> rows = messageMapper.selectPageByConversation(
                conversation.getId(), spaceId, ownerSubject, offset, safeSize);
        long total = messageMapper.countByConversation(conversation.getId(), spaceId, ownerSubject);
        int totalPages = (int) Math.max(1, Math.ceil((double) total / safeSize));
        List<MessageView> content = new ArrayList<>(rows.size());
        Map<Long, List<ContextReference>> refsByMessage = loadReferencesForMessages(
                conversationId, spaceId, ownerSubject, rows);
        for (AiMessage row : rows) {
            content.add(toMessageView(row, refsByMessage.getOrDefault(row.getId(), List.of())));
        }
        return new MessagePageResponse(
                Collections.unmodifiableList(content), safePage, safeSize, total, totalPages);
    }

    private Map<Long, List<ContextReference>> loadReferencesForMessages(
            Long conversationId, Long spaceId, String ownerSubject, List<AiMessage> messages) {
        List<Long> assistantIds = new ArrayList<>();
        for (AiMessage message : messages) {
            if (ROLE_ASSISTANT.equals(message.getRole())) {
                assistantIds.add(message.getId());
            }
        }
        if (assistantIds.isEmpty()) {
            return Map.of();
        }
        List<AiMessageReference> rows = messageReferenceMapper.selectByConversationMessages(
                conversationId, spaceId, ownerSubject, assistantIds);
        Map<Long, List<ContextReference>> result = new java.util.HashMap<>();
        for (AiMessageReference row : rows) {
            result.computeIfAbsent(row.getMessageId(), k -> new ArrayList<>())
                    .add(new ContextReference(
                            row.getReferenceType(), row.getEntityId(),
                            row.getTitle(), row.getSnippet()));
        }
        return result;
    }

    /**
     * Tutor chat turn. USER message is committed first; provider call is
     * outside any DB transaction; ASSISTANT message is committed after.
     */
    public com.aistudy.server.ai.dto.AiDto.SendMessageResponse sendMessage(
            String ownerSubject,
            Long spaceId,
            Long conversationId,
            String content) {
        requireAiEnabled(ownerSubject);
        requireSpace(ownerSubject, spaceId);
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        int maxUserChars = Math.max(1, aiProperties.getContext().getMaxUserMessageChars());
        if (trimmed.length() > maxUserChars) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "content must be at most " + maxUserChars + " characters");
        }
        AiConversation conversation = requireConversation(ownerSubject, spaceId, conversationId);
        if (!STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "conversation is not ACTIVE");
        }

        AiMessage userMessage = messagePersistenceService.persistUserMessage(
                ownerSubject, spaceId, conversation, trimmed);

        List<AiChatMessage> history = loadHistory(conversation.getId(), spaceId, ownerSubject);
        List<AiContextItem> contextItems = learningContextService.assemble(
                ownerSubject, ownerSubject, spaceId, trimmed);
        String renderedContext = learningContextService.renderContextBlock(contextItems);
        AiLearningState learningState = learningStateService.assemble(ownerSubject, spaceId);
        List<AiChatMessage> providerMessages = AiTutorPromptBuilder.build(
                history, renderedContext, learningState, trimmed);

        AiChatResponse response;
        try {
            response = aiProvider.chat(new AiChatRequest(
                    providerMessages,
                    aiProperties.getTemperature(),
                    aiProperties.getMaxOutputTokens()), ownerSubject);
        } catch (AiProviderException e) {
            aiMetrics.recordFailure(aiProperties.getProvider(), e.errorCode().name());
            throw e;
        }
        aiMetrics.recordRequest(aiProperties.getProvider(), "SUCCESS");

        AiMessage assistantMessage = messagePersistenceService.persistAssistantMessage(
                ownerSubject, spaceId, conversation, response, contextItems);

        List<ContextReference> refs = contextItems.stream()
                .map(item -> new ContextReference(
                        item.type(), item.entityId(), item.title(), item.snippet()))
                .toList();

        return new com.aistudy.server.ai.dto.AiDto.SendMessageResponse(
                conversation.getId(),
                toMessageView(userMessage, List.of()),
                toMessageView(assistantMessage, refs),
                refs);
    }

    // ==================== internals ====================

    private void requireAiEnabled(String userSubject) {
        if (!configResolver.resolveForUser(userSubject).isConfigured()) {
            aiMetrics.recordFailure(aiProperties.getProvider(), AiErrorCode.AI_NOT_CONFIGURED.name());
            throw new AiProviderException(AiErrorCode.AI_NOT_CONFIGURED,
                    "AI tutor is not configured");
        }
    }

    private void requireSpace(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
    }

    private AiConversation requireConversation(String ownerSubject, Long spaceId, Long conversationId) {
        AiConversation conversation = conversationMapper.selectByIdSpaceUser(
                conversationId, spaceId, ownerSubject);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return conversation;
    }

    private List<AiChatMessage> loadHistory(Long conversationId, Long spaceId, String userSubject) {
        int maxHistory = Math.max(0, aiProperties.getContext().getMaxHistoryMessages());
        if (maxHistory == 0) {
            return List.of();
        }
        List<AiMessage> rows = messageMapper.selectRecentForHistory(
                conversationId, spaceId, userSubject, maxHistory);
        List<AiChatMessage> history = new ArrayList<>(rows.size());
        for (AiMessage row : rows) {
            if (ROLE_USER.equals(row.getRole())) {
                history.add(AiChatMessage.user(row.getContent()));
            } else if (ROLE_ASSISTANT.equals(row.getRole())) {
                history.add(AiChatMessage.assistant(row.getContent()));
            }
            // SYSTEM rows are never persisted and never forwarded.
        }
        return history;
    }

    private static String deriveTitle(String explicit, String firstUserMessage) {
        String source = explicit;
        if (source == null || source.isBlank()) {
            source = firstUserMessage;
        }
        if (source == null || source.isBlank()) {
            return "AI Tutor";
        }
        String collapsed = source.replaceAll("\\s+", " ").trim();
        if (collapsed.length() > TITLE_MAX) {
            collapsed = collapsed.substring(0, TITLE_MAX);
        }
        return collapsed;
    }

    private static int clampSize(int size, int fallback) {
        if (size <= 0) {
            return fallback;
        }
        return Math.min(size, 100);
    }

    private static ConversationView toView(AiConversation conversation) {
        return new ConversationView(
                conversation.getId(),
                conversation.getSpaceId(),
                conversation.getTitle(),
                conversation.getStatus(),
                conversation.getLastMessageAt(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private static MessageView toMessageView(AiMessage message, List<ContextReference> references) {
        return new MessageView(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getProvider(),
                message.getModel(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getTotalTokens(),
                message.getCreatedAt(),
                references == null ? List.of() : List.copyOf(references)
        );
    }
}
