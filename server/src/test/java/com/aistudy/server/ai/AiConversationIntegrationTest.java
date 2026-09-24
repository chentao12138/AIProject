package com.aistudy.server.ai;

import com.aistudy.server.ai.dto.AiDto.ConversationPageResponse;
import com.aistudy.server.testsupport.OwnedSpaceReset;
import com.aistudy.server.ai.dto.AiDto.ConversationView;
import com.aistudy.server.ai.dto.AiDto.MessagePageResponse;
import com.aistudy.server.ai.service.AiConversationService;
import com.aistudy.server.ai.service.AiMessagePersistenceService;
import com.aistudy.server.ai.context.AiContextItem;
import com.aistudy.server.ai.entity.AiConversation;
import com.aistudy.server.ai.mapper.AiConversationMapper;
import com.aistudy.server.ai.mapper.AiMessageMapper;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiUsage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI-002 — conversation persistence, isolation, pagination, archive,
 * and short-transaction message writes (real MySQL).
 */
@SpringBootTest
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiConversationIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String USER_A = "biz-ai-user-a";
    private static final String USER_B = "biz-ai-user-b";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiConversationService conversationService;
    @Autowired
    private AiMessagePersistenceService messagePersistenceService;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private AiMessageMapper messageMapper;

    private Long spaceA;
    private Long spaceB;

    @BeforeEach
    void prepare() {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        clean();
        spaceA = insertSpace(USER_A, "AI空间A");
        spaceB = insertSpace(USER_B, "AI空间B");
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    @Test
    void createPersistsOwnerTitleAndActiveStatus() {
        ConversationView view = conversationService.createConversation(USER_A, spaceA, "我的辅导");
        assertNotNull(view.id());
        assertEquals("我的辅导", view.title());
        assertEquals("ACTIVE", view.status());
        assertEquals(spaceA, view.spaceId());
        assertNotNull(view.createdAt());

        AiConversation row = conversationMapper.selectByIdSpaceUser(view.id(), spaceA, USER_A);
        assertNotNull(row);
        assertEquals(USER_A, row.getUserSubject());
    }

    @Test
    void blankTitleFallsBackToDefault() {
        ConversationView view = conversationService.createConversation(USER_A, spaceA, "   ");
        assertEquals("AI Tutor", view.title());
    }

    @Test
    void longTitleIsBounded() {
        String longTitle = "长".repeat(200);
        ConversationView view = conversationService.createConversation(USER_A, spaceA, longTitle);
        assertTrue(view.title().length() <= 80, "title must be bounded: " + view.title().length());
    }

    @Test
    void listOnlyReturnsOwnActiveConversationsInSpace() {
        ConversationView a1 = conversationService.createConversation(USER_A, spaceA, "A1");
        conversationService.createConversation(USER_B, spaceB, "B1");
        ConversationPageResponse page = conversationService.listConversations(USER_A, spaceA, 0, 20);
        assertEquals(1, page.totalElements());
        assertEquals(a1.id(), page.content().get(0).id());
    }

    @Test
    void getForeignConversationIs404() {
        ConversationView b = conversationService.createConversation(USER_B, spaceB, "B-secret");
        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> conversationService.getConversation(USER_A, spaceA, b.id()));
        assertEquals(404, ex.getStatusCode().value());

        org.springframework.web.server.ResponseStatusException ex2 =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> conversationService.getConversation(USER_B, spaceA, b.id()));
        assertEquals(404, ex2.getStatusCode().value());
    }

    @Test
    void archiveMarksArchivedAndForeignArchiveFails() {
        ConversationView a = conversationService.createConversation(USER_A, spaceA, "to-archive");
        ConversationView archived = conversationService.archiveConversation(USER_A, spaceA, a.id());
        assertEquals("ARCHIVED", archived.status());

        ConversationView b = conversationService.createConversation(USER_B, spaceB, "b-arch");
        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> conversationService.archiveConversation(USER_A, spaceA, b.id()));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void archivedConversationIsNotListedAsActive() {
        ConversationView a = conversationService.createConversation(USER_A, spaceA, "will-archive");
        conversationService.archiveConversation(USER_A, spaceA, a.id());
        ConversationPageResponse page = conversationService.listConversations(USER_A, spaceA, 0, 20);
        assertEquals(0, page.totalElements());
    }

    @Test
    void persistUserMessageInsertsAndTouchesConversation() {
        ConversationView a = conversationService.createConversation(USER_A, spaceA, "msg");
        AiConversation conversation = conversationMapper.selectByIdSpaceUser(a.id(), spaceA, USER_A);

        var message = messagePersistenceService.persistUserMessage(
                USER_A, spaceA, conversation, "hello tutor");
        assertNotNull(message.getId());
        assertEquals("USER", message.getRole());
        assertEquals("hello tutor", message.getContent());

        AiConversation after = conversationMapper.selectByIdSpaceUser(a.id(), spaceA, USER_A);
        assertNotNull(after.getLastMessageAt());

        long count = messageMapper.countByConversation(a.id(), spaceA, USER_A);
        assertEquals(1L, count);
    }

    @Test
    void persistAssistantMessageStoresUsageAndRole() {
        ConversationView a = conversationService.createConversation(USER_A, spaceA, "msg2");
        AiConversation conversation = conversationMapper.selectByIdSpaceUser(a.id(), spaceA, USER_A);
        var assistant = messagePersistenceService.persistAssistantMessage(
                USER_A, spaceA, conversation,
                new AiChatResponse("answer", "openai-compatible", "m1",
                        new AiUsage(1, 2, 3)),
                java.util.Collections.<AiContextItem>emptyList(),
                "GENERAL");
        assertEquals("ASSISTANT", assistant.getRole());
        assertEquals("m1", assistant.getModel());
        assertEquals(1, assistant.getPromptTokens());
        assertEquals(3, assistant.getTotalTokens());
    }

    @Test
    void messageListIsChronologicalAndBounded() {
        ConversationView a = conversationService.createConversation(USER_A, spaceA, "hist");
        AiConversation conversation = conversationMapper.selectByIdSpaceUser(a.id(), spaceA, USER_A);
        for (int i = 1; i <= 5; i++) {
            messagePersistenceService.persistUserMessage(USER_A, spaceA, conversation, "m" + i);
        }
        MessagePageResponse page = conversationService.listMessages(USER_A, spaceA, a.id(), 0, 3);
        assertEquals(5, page.totalElements());
        assertEquals(3, page.content().size());
        assertEquals("m1", page.content().get(0).content());
        assertEquals("m3", page.content().get(2).content());

        MessagePageResponse page2 = conversationService.listMessages(USER_A, spaceA, a.id(), 1, 3);
        assertEquals("m4", page2.content().get(0).content());
    }

    @Test
    void foreignUserCannotListMessages() {
        ConversationView a = conversationService.createConversation(USER_A, spaceA, "private");
        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> conversationService.listMessages(USER_B, spaceA, a.id(), 0, 20));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void foreignSpaceCreateFails() {
        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> conversationService.createConversation(USER_A, spaceB, "x"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void listPaginationIsBounded() {
        for (int i = 0; i < 3; i++) {
            conversationService.createConversation(USER_A, spaceA, "c" + i);
        }
        ConversationPageResponse page = conversationService.listConversations(USER_A, spaceA, 0, 2);
        assertEquals(3, page.totalElements());
        assertEquals(2, page.content().size());
        assertEquals(2, page.totalPages());
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
        OwnedSpaceReset.forSubjects(jdbc, USER_A, USER_B);
    }

    private void assertSchema() {
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("expected " + EXPECTED_SCHEMA + " got " + actual);
        }
    }
}
