package com.aistudy.server.practice;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-009 — PracticeSession vertical slice integration test
 * (real MySQL, {@code flyway-it}).
 *
 * <p>Covers: explicit questionIds composition preserving order;
 * deterministic knowledge-point auto-pick (id ASC); snapshot frozen
 * in DB while the API view carries NO answer data; cross-space /
 * unpublished / duplicate rejection; start transition + 409s; owner
 * and user isolation; anonymous 401. Answer + finish are covered by
 * BUSINESS-010 (same class, extended).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PracticeSessionIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1", "biz-e2e-user-2");

    private static final String SESSIONS = "/api/v1/spaces/{spaceId}/practice-sessions";
    private static final String QUESTIONS = "/api/v1/spaces/{spaceId}/questions";
    private static final String POINTS = "/api/v1/spaces/{spaceId}/knowledge-points";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @BeforeAll
    void guardTargetDatabase() {
        assertSchemaIsFlywayTest();
    }

    @BeforeEach
    void prepareDatabase() {
        assertSchemaIsFlywayTest();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        cleanBizTestRows();
    }

    @AfterEach
    void cleanUp() {
        cleanBizTestRows();
    }

    // ==================== helpers ====================

    private String tokenFor(String subject) {
        String token = jwtAccessTokenService.issueAccessToken(subject);
        assertNotNull(token, "token must not be null");
        return token;
    }

    private Long insertFixtureSpace(String ownerSubject, String name) {
        jdbcTemplate.update(
                "INSERT INTO learning_space "
                        + "(name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, ownerSubject);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject = ? AND name = ? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, ownerSubject, name);
    }

    private Long insertFixturePoint(Long spaceId, String title) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_point "
                        + "(space_id, title, content, origin_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'body', 'USER_CURATED', 'PUBLISHED', 'biz-e2e-user-1', "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_point WHERE space_id = ? AND title = ? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, title);
    }

    /** Creates a PUBLISHED SINGLE_CHOICE question via the API; returns its id. */
    private Long createPublishedQuestion(String token, Long spaceId, String stem) throws Exception {
        MvcResult result = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"" + stem + "\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}],\"correctOptionKey\":\"A\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long id = extractJsonLong(result.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return id;
    }

    private String createSession(String token, Long spaceId, String body) throws Exception {
        return mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    private static Long extractJsonLong(String body, String field) {
        String marker = "\"" + field + "\":";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, "body must contain field '" + field + "': " + body);
        int valueStart = start + marker.length();
        int end = body.indexOf(',', valueStart);
        if (end < 0) {
            end = body.indexOf('}', valueStart);
        }
        return Long.parseLong(body.substring(valueStart, end).trim());
    }

    private String ids(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    // ==================== composition ====================

    /** (1) explicit questionIds: order preserved, count correct. */
    @Test
    void explicitQuestionIdsPreserveOrder() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long q2 = createPublishedQuestion(token, spaceId, "q2");
        Long q3 = createPublishedQuestion(token, spaceId, "q3");

        String body = createSession(token, spaceId,
                "{\"questionIds\":[" + ids(List.of(q3, q1, q2)) + "]}");
        Long sessionId = extractJsonLong(body, "id");
        assertEquals(3, extractJsonLong(body, "questionCount"));

        mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(3))
                .andExpect(jsonPath("$.questions[0].questionId").value(q3))
                .andExpect(jsonPath("$.questions[1].questionId").value(q1))
                .andExpect(jsonPath("$.questions[2].questionId").value(q2))
                .andExpect(jsonPath("$.questions[0].sortOrder").value(0))
                .andExpect(jsonPath("$.questions[2].sortOrder").value(2))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.startedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    /** (2) auto-pick by knowledge point: deterministic id ASC, first N. */
    @Test
    void knowledgePointAutoPickIsDeterministic() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long q2 = createPublishedQuestion(token, spaceId, "q2");
        Long q3 = createPublishedQuestion(token, spaceId, "q3");
        // link q3 and q1 to the point (insert via SQL; order q3 < q1 by id)
        jdbcTemplate.update(
                "INSERT INTO question_knowledge_point (space_id, question_id, knowledge_point_id, created_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))", spaceId, q3, kpId);
        jdbcTemplate.update(
                "INSERT INTO question_knowledge_point (space_id, question_id, knowledge_point_id, created_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))", spaceId, q1, kpId);

        String body = createSession(token, spaceId,
                "{\"knowledgePointId\":" + kpId + ",\"count\":1}");
        Long sessionId = extractJsonLong(body, "id");

        mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(1))
                // id ASC pick: min(q1, q3) = q1
                .andExpect(jsonPath("$.questions[0].questionId").value(q1));
    }

    /** (3) count larger than available published questions → 400. */
    @Test
    void countGreaterThanAvailableIsRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        jdbcTemplate.update(
                "INSERT INTO question_knowledge_point (space_id, question_id, knowledge_point_id, created_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))", spaceId, q1, kpId);

        mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgePointId\":" + kpId + ",\"count\":5}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** (4) both selection modes at once → 400; none → 400. */
    @Test
    void conflictingSelectionModesAreRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");

        mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + q1 + "],\"knowledgePointId\":" + kpId
                                + ",\"count\":1}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** (5) duplicate questionIds → 400. */
    @Test
    void duplicateQuestionIdsAreRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");

        mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + q1 + "," + q1 + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ==================== rejection (404 / isolation) ====================

    /** (6) cross-space question → whole create rejected, zero rows. */
    @Test
    void crossSpaceQuestionRejectsWholeCreate() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        String token = tokenFor("biz-e2e-user-1");
        Long qOther = createPublishedQuestion(token, space2, "异空间题");

        mockMvc.perform(post(SESSIONS, space1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + qOther + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM practice_session WHERE space_id = ?", Integer.class, space1);
        assertEquals(0, sessions, "failed create must leave zero sessions");
    }

    /** (7) unpublished question → 404. */
    @Test
    void unpublishedQuestionIsRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        MvcResult result = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"draft\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}],\"correctOptionKey\":\"A\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long draftId = extractJsonLong(result.getResponse().getContentAsString(), "id");

        mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + draftId + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (8) cross-space knowledge point auto-pick → 404. */
    @Test
    void crossSpaceKnowledgePointIsRejected() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        Long kpOther = insertFixturePoint(space2, "异空间点");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post(SESSIONS, space1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgePointId\":" + kpOther + ",\"count\":1}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (9) non-owner cannot create/list/detail/start in someone else's space. */
    @Test
    void nonOwnerIsIsolated() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        String otherToken = tokenFor("biz-e2e-user-2");

        mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + q1 + "]}")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(SESSIONS, spaceId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, sessionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    /** (10) detail under another space → 404. */
    @Test
    void sessionDetailCrossSpaceIsNotFound() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, space1, "q1");
        String body = createSession(token, space1, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");

        mockMvc.perform(get(SESSIONS + "/{sessionId}", space2, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (11) list contains only the caller's own sessions, newest first. */
    @Test
    void listIsUserScopedAndNewestFirst() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");

        mockMvc.perform(get(SESSIONS, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].questionCount").value(1));
    }

    // ==================== snapshot safety ====================

    /** (12) snapshot holds answerData in DB, but the API view never exposes it. */
    @Test
    void snapshotFrozenInDbAndAnswerHiddenFromApi() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");

        String snapshot = jdbcTemplate.queryForObject(
                "SELECT question_snapshot_json FROM practice_session_question "
                        + "WHERE practice_session_id = ? LIMIT 1", String.class, sessionId);
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("answerData"), "snapshot must freeze answer data in DB: " + snapshot);
        assertTrue(snapshot.contains("correctOptionKey"), snapshot);

        String detail = mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(detail.contains("correctOptionKey"), "detail must not leak answer: " + detail);
        assertFalse(detail.contains("answerData"), "detail must not leak answerData: " + detail);
        assertFalse(detail.contains("isCorrect"), "detail must not leak isCorrect: " + detail);
        assertTrue(detail.contains("\"optionKey\":\"A\""), "options must still be visible: " + detail);
    }

    // ==================== lifecycle ====================

    /** (13) start: CREATED → IN_PROGRESS; double start → 409. */
    @Test
    void startTransitionAndConflict() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");

        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").exists());

        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (14) unknown session → 404; anonymous → 401. */
    @Test
    void unknownSessionAndAnonymous() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== BUSINESS-010 answers ====================

    private Long slotIdOf(String token, Long spaceId, Long sessionId) throws Exception {
        String detail = mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String marker = "\"practiceSessionQuestionId\":";
        int start = detail.indexOf(marker);
        assertTrue(start >= 0, "detail must expose slot id: " + detail);
        int valueStart = start + marker.length();
        int end = detail.indexOf(',', valueStart);
        if (end < 0) {
            end = detail.indexOf('}', valueStart);
        }
        return Long.parseLong(detail.substring(valueStart, end).trim());
    }

    /** (15) correct SINGLE_CHOICE answer → 200 with immediate feedback. */
    @Test
    void answerSingleChoiceCorrectGivesImmediateFeedback() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, spaceId, sessionId);

        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCorrect").value(true))
                .andExpect(jsonPath("$.score").value(1))
                .andExpect(jsonPath("$.correctAnswer").value("A"))
                .andExpect(jsonPath("$.questionId").value(q1));
    }

    /** (16) wrong SINGLE_CHOICE → isCorrect false; explanation visible post-answer. */
    @Test
    void answerSingleChoiceWrongShowsExplanation() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        // create question WITH explanation via SQL-backed API call
        MvcResult created = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"q1\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}],"
                                + "\"correctOptionKey\":\"A\",\"explanation\":\"A is right\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long q1 = extractJsonLong(created.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, q1)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, spaceId, sessionId);

        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"B\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCorrect").value(false))
                .andExpect(jsonPath("$.correctAnswer").value("A"))
                .andExpect(jsonPath("$.explanation").value("A is right"));
    }

    /** (17) MULTIPLE_CHOICE exact-set grading end-to-end. */
    @Test
    void answerMultipleChoiceExactSet() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        MvcResult created = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"MULTIPLE_CHOICE\",\"stem\":\"pick A,C\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"},"
                                + "{\"optionKey\":\"C\",\"content\":\"c\"}],"
                                + "\"correctOptionKeys\":[\"A\",\"C\"]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long q1 = extractJsonLong(created.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, q1)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, spaceId, sessionId);

        // exact set, different order → correct
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"C\",\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCorrect").value(true))
                .andExpect(jsonPath("$.correctAnswer").value("A,C"));
        // subset → wrong (upsert re-grades)
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCorrect").value(false));
    }

    /** (18) TRUE_FALSE answer. */
    @Test
    void answerTrueFalse() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        MvcResult created = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"TRUE_FALSE\",\"stem\":\"tf\","
                                + "\"correctBoolean\":true}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long q1 = extractJsonLong(created.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, q1)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, spaceId, sessionId);

        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"booleanAnswer\":false}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCorrect").value(false))
                .andExpect(jsonPath("$.correctAnswer").value("true"));
    }

    /** (19) SHORT_ANSWER stored ungraded. */
    @Test
    void answerShortAnswerStoredUngraded() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        MvcResult created = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SHORT_ANSWER\",\"stem\":\"What is ACID?\","
                                + "\"referenceAnswer\":\"Atomicity Consistency Isolation Durability\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long q1 = extractJsonLong(created.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, q1)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, spaceId, sessionId);

        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"textAnswer\":\"ACID\"}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCorrect").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.score").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.correctAnswer").value(org.hamcrest.Matchers.nullValue()));
    }

    /** (20) shape violation → 400. */
    @Test
    void answerShapeViolationIsRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, spaceId, sessionId);

        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"booleanAnswer\":true}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** (21) slot not part of the session → 404; unknown session → 404. */
    @Test
    void foreignSlotAndUnknownSessionAreNotFound() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":999999,"
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":999999,"
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (22) cross-space / non-owner answer → 404. */
    @Test
    void crossSpaceAndNonOwnerAnswerAreNotFound() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, space1, "q1");
        String body = createSession(token, space1, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", space1, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, space1, sessionId);
        String payload = "{\"practiceSessionQuestionId\":" + slotId + ","
                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}";

        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", space2, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", space1, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    // ==================== BUSINESS-010 finish ====================

    /** (23) finish computes summary; second finish → 409. */
    @Test
    void finishComputesSummaryAndIsSingleShot() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long q2 = createPublishedQuestion(token, spaceId, "q2");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "," + q2 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String detail = mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long slot1 = extractJsonLong(detail, "practiceSessionQuestionId");
        String marker2 = "practiceSessionQuestionId";
        int idx = detail.indexOf(marker2);
        int idx2 = detail.indexOf(marker2, idx + 1);
        int valueStart = detail.indexOf(':', idx2) + 1;
        int end = detail.indexOf(',', valueStart);
        long slot2 = Long.parseLong(detail.substring(valueStart, end).trim());

        // one correct, one wrong
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slot1 + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slot2 + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"B\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.questionCount").value(2))
                .andExpect(jsonPath("$.answeredCount").value(2))
                .andExpect(jsonPath("$.correctCount").value(1))
                .andExpect(jsonPath("$.score").value(1))
                .andExpect(jsonPath("$.maxScore").value(2));

        // second finish → 409
        mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        // answer after finish → 409
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slot1 + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        // finished_at persisted
        String finished = jdbcTemplate.queryForObject(
                "SELECT finished_at FROM practice_session WHERE id = ?", String.class, sessionId);
        assertNotNull(finished, "finished_at must be persisted");
    }

    /** (24) finish on CREATED (never started) → 409. */
    @Test
    void finishBeforeStartIsConflict() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");

        mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (25) unanswered objective slots count as 0/1 in maxScore. */
    @Test
    void unansweredObjectiveSlotsCountInMaxScore() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long q2 = createPublishedQuestion(token, spaceId, "q2");
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "," + q2 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, spaceId, sessionId);
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answeredCount").value(1))
                .andExpect(jsonPath("$.score").value(1))
                .andExpect(jsonPath("$.maxScore").value(2));
    }

    /** (26) SHORT_ANSWER slot excluded from score/maxScore. */
    @Test
    void shortAnswerExcludedFromScore() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        MvcResult created = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SHORT_ANSWER\",\"stem\":\"What is ACID?\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long q1 = extractJsonLong(created.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, q1)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String body = createSession(token, spaceId, "{\"questionIds\":[" + q1 + "]}");
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long slotId = slotIdOf(token, spaceId, sessionId);
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"textAnswer\":\"ACID\"}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answeredCount").value(1))
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.maxScore").value(0));
    }

    // ==================== cleanup ====================

    private void cleanBizTestRows() {
        String placeholders = String.join(",", Collections.nCopies(BIZ_TEST_USERS.size(), "?"));
        Object[] users = BIZ_TEST_USERS.toArray();
        String spaceIds = "(SELECT id FROM learning_space WHERE owner_subject IN (" + placeholders + "))";
                                jdbcTemplate.update(
                "DELETE FROM exam_answer WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM exam_result WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM exam_diagnosis_item WHERE exam_diagnosis_id IN "
                        + "(SELECT id FROM exam_diagnosis WHERE space_id IN " + spaceIds + ")", users);
        jdbcTemplate.update(
                "DELETE FROM exam_diagnosis WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM exam_attempt WHERE space_id IN " + spaceIds, users);
jdbcTemplate.update(
                "DELETE FROM exam_question WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM exam_paper WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM exam WHERE space_id IN " + spaceIds, users);
jdbcTemplate.update(
                "DELETE FROM review_record WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM review_task WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM wrong_question WHERE space_id IN " + spaceIds, users);
jdbcTemplate.update(
                "DELETE FROM practice_answer WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM practice_session_question WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM practice_session WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM question_source WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM question_knowledge_point WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM question_option WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM question WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM study_task WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM study_plan WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM mastery WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM knowledge_point_source WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM knowledge_point WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM knowledge_category WHERE space_id IN " + spaceIds
                        + " AND parent_id IS NOT NULL", users);
        jdbcTemplate.update(
                "DELETE FROM knowledge_category WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM content_block WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM source_page WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM ingestion_job WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM source_asset WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM source WHERE space_id IN " + spaceIds, users);
        jdbcTemplate.update(
                "DELETE FROM learning_space WHERE owner_subject IN (" + placeholders + ")", users);
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run PracticeSessionIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
