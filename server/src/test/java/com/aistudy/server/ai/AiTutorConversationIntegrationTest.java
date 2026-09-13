package com.aistudy.server.ai;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.ai.dto.AiDto.SendMessageResponse;
import com.aistudy.server.ai.dto.AiDto.ConversationView;
import com.aistudy.server.ai.mapper.AiConversationMapper;
import com.aistudy.server.ai.mapper.AiMessageMapper;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiErrorCode;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.provider.AiProviderException;
import com.aistudy.server.ai.provider.AiUsage;
import com.aistudy.server.ai.service.AiConversationService;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI-004 — send-message orchestration with real DB and mock provider.
 * Proves network boundary: USER commit → provider → ASSISTANT commit.
 */
@SpringBootTest
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiTutorConversationIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String OWNER = "biz-ai-tutor-user";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiConversationService conversationService;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private AiMessageMapper messageMapper;
    @Autowired
    private AiProperties aiProperties;

    @MockitoBean
    private AiProvider aiProvider;

    private Long spaceId;
    private Long conversationId;

    @DynamicPropertySource
    static void aiProps(DynamicPropertyRegistry registry) {
        registry.add("aistudy.ai.enabled", () -> "true");
        registry.add("aistudy.ai.base-url", () -> "http://127.0.0.1:9");
        registry.add("aistudy.ai.api-key", () -> "test-api-key");
        registry.add("aistudy.ai.model", () -> "test-model");
        registry.add("aistudy.ai.context.max-history-messages", () -> "3");
    }

    @BeforeEach
    void prepare() {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        clean();
        spaceId = insertSpace(OWNER, "AI Tutor Space");
        ConversationView conversation = conversationService.createConversation(OWNER, spaceId, "tutor");
        conversationId = conversation.id();
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    @Test
    void successPersistsUserThenAssistantAndUpdatesLastMessageAt() {
        when(aiProvider.chat(any(), anyString())).thenReturn(new AiChatResponse(
                "这是辅导回答", "openai-compatible", "test-model",
                new AiUsage(10, 20, 30)));

        SendMessageResponse response = conversationService.sendMessage(
                OWNER, spaceId, conversationId, "请解释事务");

        assertNotNull(response.userMessage());
        assertNotNull(response.assistantMessage());
        assertEquals("USER", response.userMessage().role());
        assertEquals("ASSISTANT", response.assistantMessage().role());
        assertEquals("请解释事务", response.userMessage().content());
        assertEquals("这是辅导回答", response.assistantMessage().content());
        assertEquals(10, response.assistantMessage().promptTokens());

        List<String> roles = jdbc.queryForList(
                "SELECT role FROM ai_message WHERE conversation_id = ? ORDER BY id ASC",
                String.class, conversationId);
        assertEquals(List.of("USER", "ASSISTANT"), roles);

        assertNotNull(jdbc.queryForObject(
                "SELECT last_message_at FROM ai_conversation WHERE id = ?",
                java.sql.Timestamp.class, conversationId));
    }

    @Test
    void providerRequestContainsSystemPolicyHistoryAndCurrentMessage() {
        when(aiProvider.chat(any(), anyString())).thenReturn(new AiChatResponse(
                "ok", "openai-compatible", "test-model", AiUsage.empty()));

        conversationService.sendMessage(OWNER, spaceId, conversationId, "第一问");
        conversationService.sendMessage(OWNER, spaceId, conversationId, "第二问");

        ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiProvider, atLeastOnce()).chat(captor.capture(), anyString());
        AiChatRequest last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertFalse(last.messages().isEmpty());
        assertTrue(last.messages().get(0).content().contains("AIStudy learning tutor"));
        String joined = last.messages().stream().map(m -> m.content()).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("第一问"));
        assertTrue(joined.contains("第二问"));
        // Current message appears in the final USER turn.
        assertTrue(last.messages().get(last.messages().size() - 1).content().contains("第二问"));
    }

    @Test
    void historyIsBoundedToConfiguredMax() {
        when(aiProvider.chat(any(), anyString())).thenReturn(new AiChatResponse(
                "ok", "openai-compatible", "test-model", AiUsage.empty()));
        for (int i = 1; i <= 6; i++) {
            conversationService.sendMessage(OWNER, spaceId, conversationId, "msg-" + i);
        }
        ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiProvider, atLeastOnce()).chat(captor.capture(), anyString());
        AiChatRequest last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        // system + bounded history + current user turn
        // max-history-messages=3 means at most 3 history rows + system + current
        assertTrue(last.messages().size() <= 1 + 3 + 1,
                "history must be bounded, actual=" + last.messages().size());
        assertFalse(last.messages().stream().anyMatch(m ->
                m.content() != null && m.content().equals("msg-1")),
                "oldest history should be dropped");
    }

    @Test
    void providerFailureKeepsUserMessageAndDoesNotWriteAssistant() {
        when(aiProvider.chat(any(), anyString())).thenThrow(new AiProviderException(
                AiErrorCode.AI_PROVIDER_UNAVAILABLE, "AI provider is unavailable"));

        AiProviderException ex = assertThrows(AiProviderException.class,
                () -> conversationService.sendMessage(OWNER, spaceId, conversationId, "失败问"));
        assertEquals(AiErrorCode.AI_PROVIDER_UNAVAILABLE, ex.errorCode());

        List<String> roles = jdbc.queryForList(
                "SELECT role FROM ai_message WHERE conversation_id = ? ORDER BY id ASC",
                String.class, conversationId);
        assertEquals(List.of("USER"), roles,
                "USER message must remain committed; ASSISTANT must not exist");
        assertNotNull(jdbc.queryForObject(
                "SELECT last_message_at FROM ai_conversation WHERE id = ?",
                java.sql.Timestamp.class, conversationId));
    }

    @Test
    void blankContentIsRejected() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> conversationService.sendMessage(OWNER, spaceId, conversationId, "   "));
        verify(aiProvider, never()).chat(any(), anyString());
    }

    @Test
    void overlongContentIsRejected() {
        String longMsg = "x".repeat(aiProperties.getContext().getMaxUserMessageChars() + 1);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> conversationService.sendMessage(OWNER, spaceId, conversationId, longMsg));
        verify(aiProvider, never()).chat(any(), anyString());
    }

    @Test
    void archivedConversationRejectsSend() {
        conversationService.archiveConversation(OWNER, spaceId, conversationId);
        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> conversationService.sendMessage(
                                OWNER, spaceId, conversationId, "after archive"));
        assertEquals(409, ex.getStatusCode().value());
        verify(aiProvider, never()).chat(any(), anyString());
    }

    @Test
    void foreignUserCannotSendIntoConversation() {
        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> conversationService.sendMessage(
                                "biz-ai-other-user", spaceId, conversationId, "intrude"));
        assertEquals(404, ex.getStatusCode().value());
        verify(aiProvider, never()).chat(any(), anyString());
    }

    private Long insertSpace(String owner, String name) {
        jdbc.update("INSERT INTO learning_space "
                        + "(name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, owner);
        return jdbc.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, owner, name);
    }

    private void clean() {
        jdbc.update("DELETE FROM ai_message WHERE conversation_id IN "
                + "(SELECT id FROM ai_conversation WHERE user_subject=?)", OWNER);
        jdbc.update("DELETE FROM ai_conversation WHERE user_subject=?", OWNER);
        jdbc.update("DELETE FROM learning_space WHERE owner_subject=?", OWNER);
    }

    private void assertSchema() {
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("expected " + EXPECTED_SCHEMA + " got " + actual);
        }
    }
}
