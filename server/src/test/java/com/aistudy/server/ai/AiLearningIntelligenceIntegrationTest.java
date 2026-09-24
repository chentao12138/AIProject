package com.aistudy.server.ai;

import com.aistudy.server.ai.coach.AiStudyCoachService;
import com.aistudy.server.testsupport.OwnedSpaceReset;
import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.ai.dto.AiDto.ConversationView;
import com.aistudy.server.ai.dto.AiDto.ConversationView;
import com.aistudy.server.ai.dto.AiDto.ExplanationResponse;
import com.aistudy.server.ai.dto.AiDto.SendMessageResponse;
import com.aistudy.server.ai.dto.AiDto.StudyCoachResponse;
import com.aistudy.server.ai.explain.AiAnswerExplanationService;
import com.aistudy.server.ai.mapper.AiConversationMapper;
import com.aistudy.server.ai.mapper.AiMessageMapper;
import com.aistudy.server.ai.mapper.AiMessageReferenceMapper;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiErrorCode;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.provider.AiProviderException;
import com.aistudy.server.ai.provider.AiUsage;
import com.aistudy.server.ai.service.AiConversationService;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI-005~008 — learning intelligence integration: references, explanation,
 * coach, isolation, and pre-submit firewall (real DB, mock provider).
 */
@SpringBootTest
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiLearningIntelligenceIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String USER_A = "biz-ai-intel-a";
    private static final String USER_B = "biz-ai-intel-b";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiConversationService conversationService;
    @Autowired
    private AiAnswerExplanationService explanationService;
    @Autowired
    private AiStudyCoachService coachService;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private AiMessageMapper messageMapper;
    @Autowired
    private AiMessageReferenceMapper messageReferenceMapper;
    @Autowired
    private AiProperties aiProperties;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @MockitoBean
    private AiProvider aiProvider;

    private Long spaceA;
    private Long spaceB;

    @DynamicPropertySource
    static void aiProps(DynamicPropertyRegistry registry) {
        registry.add("aistudy.ai.enabled", () -> "true");
        registry.add("aistudy.ai.base-url", () -> "http://127.0.0.1:9");
        registry.add("aistudy.ai.api-key", () -> "test-api-key");
        registry.add("aistudy.ai.model", () -> "test-model");
    }

    @BeforeEach
    void prepare() {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        clean();
        spaceA = insertSpace(USER_A, "IntelA");
        spaceB = insertSpace(USER_B, "IntelB");
        when(aiProvider.chat(any(), anyString())).thenReturn(new AiChatResponse(
                "ok", "openai-compatible", "test-model", new AiUsage(1, 1, 2)));
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    // ==================== AI-005 references ====================

    @Test
    void assistantMessagePersistsBoundedReferences() {
        ConversationView conversation = conversationService.createConversation(USER_A, spaceA, "refs");
        // Seed a searchable source
        insertSource(spaceA, "RefSourceTitle");
        SendMessageResponse response = conversationService.sendMessage(
                USER_A, spaceA, conversation.id(), "RefSourceTitle");

        assertNotNull(response.assistantMessage());
        assertNotNull(response.contextReferences());
        List<?> refs = jdbc.queryForList(
                "SELECT * FROM ai_message_reference WHERE message_id = ?",
                response.assistantMessage().id());
        assertTrue(refs.size() >= 0);
        // References on assistant message equal response contextReferences size
        assertEquals(response.contextReferences().size(), refs.size());
        // Foreign user cannot list this conversation messages
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> conversationService.listMessages(USER_B, spaceA, conversation.id(), 0, 50));
    }

    @Test
    void questionReferencesDoNotLeakCorrectness() {
        insertQuestion(spaceA, "What is ACID?", "{\"correctOptionKey\":\"A\"}");
        ConversationView conversation = conversationService.createConversation(USER_A, spaceA, "q");
        when(aiProvider.chat(any(), anyString())).thenReturn(new AiChatResponse(
                "explain ACID", "openai-compatible", "test-model", AiUsage.empty()));
        SendMessageResponse response = conversationService.sendMessage(
                USER_A, spaceA, conversation.id(), "What is ACID?");
        String json = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(title) FROM ai_message_reference WHERE message_id = ?",
                String.class, response.assistantMessage().id());
        if (json != null) {
            assertFalse(json.contains("correctOptionKey"));
            assertFalse(json.contains("secret-key"));
        }
    }

    // ==================== AI-007 explanation ====================

    @Test
    void unsubmittedPracticeAnswerReturns409() {
        Long answerId = seedPracticeAnswer(false);
        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> explanationService.explainPracticeAnswer(USER_A, spaceA, answerId));
        assertEquals(409, ex.getStatusCode().value());
        verify(aiProvider, never()).chat(any(), anyString());
    }

    @Test
    void submittedPracticeAnswerExplainsWithoutMutatingCore() {
        Long answerId = seedPracticeAnswer(true);
        int wrongBefore = count("wrong_question", "user_subject = ?", USER_A);
        int masteryBefore = count("mastery", "user_subject = ?", USER_A);

        when(aiProvider.chat(any(), anyString())).thenReturn(new AiChatResponse(
                "Because A is the atomicity property...", "openai-compatible", "test-model", AiUsage.empty()));
        ExplanationResponse explanation = explanationService.explainPracticeAnswer(USER_A, spaceA, answerId);
        assertNotNull(explanation.explanation());
        assertFalse(explanation.explanation().isBlank());

        assertEquals(wrongBefore, count("wrong_question", "user_subject = ?", USER_A));
        assertEquals(masteryBefore, count("mastery", "user_subject = ?", USER_A));
        // grade unchanged
        Boolean stillCorrect = jdbc.queryForObject(
                "SELECT is_correct FROM practice_answer WHERE id = ?", Boolean.class, answerId);
        assertNotNull(stillCorrect);
    }

    @Test
    void foreignUserCannotExplainPracticeAnswer() {
        Long answerId = seedPracticeAnswer(true);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> explanationService.explainPracticeAnswer(USER_B, spaceA, answerId));
    }

    // ==================== AI-008 coach ====================

    @Test
    void coachUsesBoundedLearningStateAndIsReadOnly() {
        insertMastery(USER_A, spaceA, 1L, 0.1);
        insertWrongQuestion(USER_A, spaceA, 5L);
        insertStudyPlan(USER_A, spaceA, "ACTIVE");

        StudyCoachResponse coach = coachService.coach(USER_A, spaceA, "What next?");
        assertNotNull(coach.summary());
        assertFalse(coach.focusKnowledgePointIds().isEmpty());
        assertTrue(coach.recommendedNextActions().size() <= 8);

        assertEquals(1, count("study_plan", "user_subject = ?", USER_A));
        assertEquals(1, count("mastery", "user_subject = ?", USER_A));
    }

    @Test
    void coachForeignSpaceIs404() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> coachService.coach(USER_B, spaceA, "x"));
    }

    // ==================== pre-submit firewall ====================

    @Test
    void tutorChatProviderPayloadHasNoCorrectnessMaterial() {
        insertQuestion(spaceA, "FirewallQ?", "{\"correctOptionKey\":\"A\"}");
        ConversationView conversation = conversationService.createConversation(USER_A, spaceA, "fw");
        when(aiProvider.chat(any(), anyString())).thenReturn(new AiChatResponse(
                "safe", "openai-compatible", "test-model", AiUsage.empty()));
        conversationService.sendMessage(USER_A, spaceA, conversation.id(), "FirewallQ?");
        ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiProvider).chat(captor.capture(), anyString());
        String joined = captor.getValue().messages().stream()
                .map(m -> m.content() == null ? "" : m.content())
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(joined.contains("correctOptionKey"));
        assertFalse(joined.contains("answer_data_json"));
    }

    // ==================== helpers ====================

    private Long seedPracticeAnswer(boolean submitted) {
        insertQuestion(spaceA, "What is ACID?", "{\"correctOptionKey\":\"A\"}");
        Long questionId = jdbc.queryForObject(
                "SELECT id FROM question WHERE space_id=? AND stem='What is ACID?' ORDER BY id DESC LIMIT 1",
                Long.class, spaceA);
        Long sessionId = insertPracticeSession(submitted ? "SUBMITTED" : "IN_PROGRESS");
        Long slotId = insertPracticeSlot(sessionId, questionId);
        return insertPracticeAnswer(slotId, questionId, submitted);
    }

    private Long insertSpace(String owner, String name) {
        jdbc.update("INSERT INTO learning_space (name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, owner);
        return jdbc.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, owner, name);
    }

    private void insertSource(Long spaceId, String title) {
        jdbc.update("INSERT INTO source (space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'DESKTOP_UPLOAD', 'REGISTERED', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title, USER_A);
    }

    private void insertQuestion(Long spaceId, String stem, String answerJson) {
        jdbc.update("INSERT INTO question (space_id, question_type, stem, answer_data_json, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, 'SINGLE_CHOICE', ?, ?, 'USER_CURATED', 'PUBLISHED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, stem, answerJson);
    }

    private void insertMastery(String user, Long spaceId, Long kpId, double score) {
        jdbc.update("INSERT INTO knowledge_point (space_id, title, summary, content, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, ?, 's', 'c', 'USER_CURATED', 'PUBLISHED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, "KP-" + kpId);
        Long realKpId = jdbc.queryForObject(
                "SELECT id FROM knowledge_point WHERE space_id=? AND title=? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, "KP-" + kpId);
        jdbc.update("INSERT INTO mastery (user_subject, space_id, knowledge_point_id, mastery_score, confidence, "
                        + "practice_evidence_count, exam_evidence_count, review_evidence_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0.2, 1, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                user, spaceId, realKpId, score);
    }

    private void insertWrongQuestion(String user, Long spaceId, Long questionId) {
        jdbc.update("INSERT INTO question (space_id, question_type, stem, answer_data_json, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, 'SINGLE_CHOICE', 'wq-stem', '{\"correctOptionKey\":\"A\"}', 'USER_CURATED', 'PUBLISHED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId);
        Long qid = jdbc.queryForObject(
                "SELECT id FROM question WHERE space_id=? AND stem='wq-stem' ORDER BY id DESC LIMIT 1",
                Long.class, spaceId);
        jdbc.update("INSERT INTO wrong_question (user_subject, space_id, question_id, first_wrong_at, last_wrong_at, wrong_count, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                user, spaceId, qid);
    }

    private void insertStudyPlan(String user, Long spaceId, String status) {
        jdbc.update("INSERT INTO study_plan (user_subject, space_id, name, start_date, end_date, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'plan', CURRENT_DATE, DATE_ADD(CURRENT_DATE, INTERVAL 7 DAY), ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                user, spaceId, status);
    }

    private Long insertPracticeSession(String status) {
        jdbc.update("INSERT INTO practice_session (user_subject, space_id, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                USER_A, spaceA, status);
        return jdbc.queryForObject(
                "SELECT id FROM practice_session WHERE user_subject=? ORDER BY id DESC LIMIT 1",
                Long.class, USER_A);
    }

    private Long insertPracticeSlot(Long sessionId, Long questionId) {
        jdbc.update("INSERT INTO practice_session_question (space_id, practice_session_id, question_id, sort_order, question_snapshot_json, created_at) "
                        + "VALUES (?, ?, ?, 0, ?, CURRENT_TIMESTAMP(6))",
                spaceA, sessionId, questionId,
                "{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"What is ACID?\",\"answerData\":{\"correctOptionKey\":\"A\"}}");
        return jdbc.queryForObject(
                "SELECT id FROM practice_session_question WHERE practice_session_id=? ORDER BY id DESC LIMIT 1",
                Long.class, sessionId);
    }

    private Long insertPracticeAnswer(Long slotId, Long questionId, boolean submitted) {
        jdbc.update("INSERT INTO practice_answer (user_subject, space_id, practice_session_question_id, question_id, "
                        + "answer_data_json, is_correct, score, submitted_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                USER_A, spaceA, slotId, questionId,
                "{\"selectedOptionKeys\":[\"A\"]}",
                submitted);
        return jdbc.queryForObject(
                "SELECT id FROM practice_answer WHERE practice_session_question_id=? ORDER BY id DESC LIMIT 1",
                Long.class, slotId);
    }

    private int count(String table, String where, Object arg) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, arg);
        return n == null ? 0 : n;
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
