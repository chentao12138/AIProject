package com.aistudy.server.mastery;

import com.aistudy.server.spike.auth.SpikeJwtTokenService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-014 — Mastery vertical slice integration test.
 *
 * <p>Real MySQL ({@code flyway-it}), MockMvc + real JWT, schema guard
 * against {@code aistudy_flyway_test}. Drives practice sessions and
 * exams END-TO-END through the public APIs so the recompute hooks
 * (practice finish / exam submit, same transaction) are exercised for
 * real. Review evidence is seeded directly (KP-targeted review tasks
 * have no creation API in V1; the completion API only records history
 * for them).
 *
 * <p>Evidence STATUS contract: IN_PROGRESS practice sessions and exam
 * attempts NEVER contribute to mastery — only SUBMITTED evidence
 * counts (AUTORUN-CONTINUE-014_016 §3.1/§3.2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MasteryVerticalSliceIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1", "biz-e2e-user-2");

    private static final String MASTERY = "/api/v1/spaces/{spaceId}/mastery";
    private static final String QUESTIONS = "/api/v1/spaces/{spaceId}/questions";
    private static final String SESSIONS = "/api/v1/spaces/{spaceId}/practice-sessions";
    private static final String EXAMS = "/api/v1/spaces/{spaceId}/exams";
    private static final String ATTEMPTS = "/api/v1/spaces/{spaceId}/exam-attempts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpikeJwtTokenService spikeJwtTokenService;

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
        String token = spikeJwtTokenService.issueAccessToken(subject);
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

    /** Creates + publishes a SINGLE_CHOICE question (correct = A), optional KP links. */
    private Long createPublishedQuestion(String token, Long spaceId, String stem,
                                         Long... kpIds) throws Exception {
        String kpJson = kpIds.length == 0 ? "" : ",\"knowledgePointIds\":["
                + String.join(",", java.util.Arrays.stream(kpIds)
                .map(String::valueOf).toList()) + "]";
        MvcResult result = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"" + stem + "\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}],"
                                + "\"correctOptionKey\":\"A\"" + kpJson + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long id = extractJsonLong(result.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return id;
    }

    /** Creates + publishes a SHORT_ANSWER question, optional KP links. */
    private Long createPublishedShortAnswer(String token, Long spaceId, String stem,
                                            Long kpId) throws Exception {
        MvcResult result = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SHORT_ANSWER\",\"stem\":\"" + stem + "\","
                                + "\"referenceAnswer\":\"ACID\",\"knowledgePointIds\":[" + kpId + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long id = extractJsonLong(result.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return id;
    }

    /** Creates + starts a practice session over the given question ids. */
    private Long startPractice(String token, Long spaceId, Long... questionIds) throws Exception {
        String body = mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + String.join(",",
                                java.util.Arrays.stream(questionIds)
                                        .map(String::valueOf).toList()) + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return sessionId;
    }

    private Long slotIdOf(String token, Long spaceId, Long sessionId) throws Exception {
        return slotIdsOf(token, spaceId, sessionId).get(0);
    }

    /** All slot ids of a session in display order (multi-question sessions). */
    private List<Long> slotIdsOf(String token, Long spaceId, Long sessionId) throws Exception {
        String detail = mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = new java.util.ArrayList<>();
        String marker = "\"practiceSessionQuestionId\":";
        int from = 0;
        while (true) {
            int start = detail.indexOf(marker, from);
            if (start < 0) {
                break;
            }
            int valueStart = start + marker.length();
            int end = detail.indexOf(',', valueStart);
            if (end < 0) {
                end = detail.indexOf('}', valueStart);
            }
            ids.add(Long.parseLong(detail.substring(valueStart, end).trim()));
            from = end;
        }
        assertTrue(!ids.isEmpty(), "detail must expose at least one slot id: " + detail);
        return ids;
    }

    private void answerPractice(String token, Long spaceId, Long sessionId, Long slotId,
                                String key) throws Exception {
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"" + key + "\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void answerPracticeText(String token, Long spaceId, Long sessionId, Long slotId)
            throws Exception {
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"textAnswer\":\"ACID\"}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void finishPractice(String token, Long spaceId, Long sessionId) throws Exception {
        mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** One correct-answer practice run over the given questions, fully SUBMITTED. */
    private void runPracticeCorrect(String token, Long spaceId, Long... questionIds)
            throws Exception {
        Long sessionId = startPractice(token, spaceId, questionIds);
        for (Long slotId : slotIdsOf(token, spaceId, sessionId)) {
            // one slot per question; correct key = A
            answerPractice(token, spaceId, sessionId, slotId, "A");
        }
        finishPractice(token, spaceId, sessionId);
    }

    private Long createExamAndPublish(String token, Long spaceId, Long q1) throws Exception {
        String body = mockMvc.perform(post(EXAMS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"exam\",\"durationMinutes\":60,"
                                + "\"questions\":[{\"questionId\":" + q1 + ",\"score\":1}]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long examId = extractJsonLong(body, "id");
        mockMvc.perform(post(EXAMS + "/{examId}/publish", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return examId;
    }

    private Long startAttempt(String token, Long spaceId, Long examId) throws Exception {
        String body = mockMvc.perform(post(EXAMS + "/{examId}/sessions", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long attemptId = extractJsonLong(body, "id");
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/start", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return attemptId;
    }

    private Long examSlotIdOf(String token, Long spaceId, Long attemptId) throws Exception {
        String detail = mockMvc.perform(get(ATTEMPTS + "/{attemptId}", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String marker = "\"examQuestionId\":";
        int start = detail.indexOf(marker);
        assertTrue(start >= 0, "attempt detail must expose examQuestionId: " + detail);
        int valueStart = start + marker.length();
        int end = detail.indexOf(',', valueStart);
        if (end < 0) {
            end = detail.indexOf('}', valueStart);
        }
        return Long.parseLong(detail.substring(valueStart, end).trim());
    }

    private void answerExam(String token, Long spaceId, Long attemptId, Long slotId, String key)
            throws Exception {
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"" + key + "\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void submitExam(String token, Long spaceId, Long attemptId) throws Exception {
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** Seeds one KP-targeted review record (no creation API in V1). */
    private void seedKpReviewRecord(Long spaceId, String userSubject, Long kpId,
                                    String completedAt) {
        jdbcTemplate.update(
                "INSERT INTO review_task "
                        + "(user_subject, space_id, target_type, target_id, reason, due_at, "
                        + "priority, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'KNOWLEDGE_POINT', ?, NULL, CURRENT_TIMESTAMP(6), "
                        + "'LOW', 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                userSubject, spaceId, kpId);
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM review_task WHERE space_id = ? AND target_type = 'KNOWLEDGE_POINT' "
                        + "AND target_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, kpId);
        jdbcTemplate.update(
                "INSERT INTO review_record "
                        + "(review_task_id, user_subject, space_id, result, completed_at, created_at) "
                        + "VALUES (?, ?, ?, 'CORRECT', ?, CURRENT_TIMESTAMP(6))",
                taskId, userSubject, spaceId, completedAt);
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

    private static String extractJsonString(String body, String field) {
        String marker = "\"" + field + "\":\"";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, "body must contain field '" + field + "': " + body);
        int valueStart = start + marker.length();
        int end = body.indexOf('"', valueStart);
        return body.substring(valueStart, end);
    }

    // ==================== tests ====================

    /** (1) no evidence → no row: list empty, detail 404. */
    @Test
    void noEvidenceMeansNoRow() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        createPublishedQuestion(tokenFor("biz-e2e-user-1"), spaceId, "q1", kpId);
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery",
                        spaceId, kpId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (2) practice SUBMITTED correct answer contributes 1/1 → score 1.0. */
    @Test
    void practiceCorrectContributes() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);

        runPracticeCorrect(token, spaceId, q1);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].knowledgePointId").value(kpId))
                .andExpect(jsonPath("$[0].masteryScore").value(1.0))
                .andExpect(jsonPath("$[0].confidence").value(0.2))
                .andExpect(jsonPath("$[0].practiceEvidenceCount").value(1))
                .andExpect(jsonPath("$[0].examEvidenceCount").value(0))
                .andExpect(jsonPath("$[0].reviewEvidenceCount").value(0))
                .andExpect(jsonPath("$[0].lastEvidenceAt").exists());
    }

    /** (3) practice SUBMITTED wrong answer contributes 0/1 → score 0.0. */
    @Test
    void practiceWrongContributes() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);

        Long sessionId = startPractice(token, spaceId, q1);
        Long slotId = slotIdOf(token, spaceId, sessionId);
        answerPractice(token, spaceId, sessionId, slotId, "B"); // wrong
        finishPractice(token, spaceId, sessionId);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].masteryScore").value(0.0))
                .andExpect(jsonPath("$[0].practiceEvidenceCount").value(1));
    }

    /** (4) IN_PROGRESS practice answers do NOT contribute to mastery. */
    @Test
    void inProgressPracticeDoesNotContribute() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        Long q2 = createPublishedQuestion(token, spaceId, "q2", kpId);

        // IN_PROGRESS session with a correct answer — never finished
        Long open = startPractice(token, spaceId, q1);
        answerPractice(token, spaceId, open, slotIdOf(token, spaceId, open), "A");

        // a later SUBMITTED session triggers recompute for the SAME kp
        runPracticeCorrect(token, spaceId, q2);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].practiceEvidenceCount").value(1))
                .andExpect(jsonPath("$[0].masteryScore").value(1.0));
    }

    /** (5) exam SUBMITTED contributes via the exam submit hook. */
    @Test
    void examSubmitContributes() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        Long examId = createExamAndPublish(token, spaceId, q1);

        Long attemptId = startAttempt(token, spaceId, examId);
        Long slotId = examSlotIdOf(token, spaceId, attemptId);
        answerExam(token, spaceId, attemptId, slotId, "A");
        submitExam(token, spaceId, attemptId);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].masteryScore").value(1.0))
                .andExpect(jsonPath("$[0].examEvidenceCount").value(1))
                .andExpect(jsonPath("$[0].practiceEvidenceCount").value(0));
    }

    /** (6) IN_PROGRESS exam answers do NOT contribute. */
    @Test
    void inProgressExamDoesNotContribute() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        Long examId = createExamAndPublish(token, spaceId, q1);

        // attempt 1 answered but never submitted
        Long open = startAttempt(token, spaceId, examId);
        answerExam(token, spaceId, open, examSlotIdOf(token, spaceId, open), "A");

        // attempt 2 fully submitted → recompute sees ONLY attempt 2
        Long submitted = startAttempt(token, spaceId, examId);
        Long slotId = examSlotIdOf(token, spaceId, submitted);
        answerExam(token, spaceId, submitted, slotId, "B"); // wrong
        submitExam(token, spaceId, submitted);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].examEvidenceCount").value(1))
                .andExpect(jsonPath("$[0].masteryScore").value(0.0));
    }

    /** (7) practice + exam evidence aggregate into one score. */
    @Test
    void practiceAndExamAggregate() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        Long q2 = createPublishedQuestion(token, spaceId, "q2", kpId);

        // practice: q1 correct, q2 wrong → 1/2
        Long sessionId = startPractice(token, spaceId, q1, q2);
        List<Long> slots = slotIdsOf(token, spaceId, sessionId);
        answerPractice(token, spaceId, sessionId, slots.get(0), "A"); // q1 correct
        answerPractice(token, spaceId, sessionId, slots.get(1), "B"); // q2 wrong
        finishPractice(token, spaceId, sessionId);

        // exam: q1 correct → +1/1 → total 2/3
        Long examId = createExamAndPublish(token, spaceId, q1);
        Long attemptId = startAttempt(token, spaceId, examId);
        answerExam(token, spaceId, attemptId, examSlotIdOf(token, spaceId, attemptId), "A");
        submitExam(token, spaceId, attemptId);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].practiceEvidenceCount").value(2))
                .andExpect(jsonPath("$[0].examEvidenceCount").value(1))
                .andExpect(jsonPath("$[0].masteryScore").value(2.0 / 3.0))
                .andExpect(jsonPath("$[0].confidence").value(0.6));
    }

    /** (8) SHORT_ANSWER (ungraded) contributes no graded evidence. */
    @Test
    void shortAnswerExcluded() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedShortAnswer(token, spaceId, "q1", kpId);

        Long sessionId = startPractice(token, spaceId, q1);
        answerPracticeText(token, spaceId, sessionId, slotIdOf(token, spaceId, sessionId));
        finishPractice(token, spaceId, sessionId);

        // The recompute policy decision: any recompute upserts the
        // current state — including ZERO graded evidence (score 0.0,
        // confidence 0.0, all counts 0). The ungraded answer must
        // NEVER enter practiceEvidenceCount.
        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].practiceEvidenceCount").value(0))
                .andExpect(jsonPath("$[0].examEvidenceCount").value(0))
                .andExpect(jsonPath("$[0].reviewEvidenceCount").value(0))
                .andExpect(jsonPath("$[0].masteryScore").value(0.0))
                .andExpect(jsonPath("$[0].confidence").value(0.0));
    }

    /** (9) questions without knowledge-point links are ignored. */
    @Test
    void questionWithoutKpIgnored() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1"); // no KP link

        runPracticeCorrect(token, spaceId, q1);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** (10) multiple knowledge points recompute independently. */
    @Test
    void multipleKpsRecomputeIndependently() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kp1 = insertFixturePoint(spaceId, "点1");
        Long kp2 = insertFixturePoint(spaceId, "点2");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kp1);
        Long q2 = createPublishedQuestion(token, spaceId, "q2", kp2);
        Long q3 = createPublishedQuestion(token, spaceId, "q3", kp1, kp2); // linked to BOTH

        // q1 correct → kp1 1/1; q2 correct → kp2 1/1; q3 correct → both +1
        runPracticeCorrect(token, spaceId, q1, q2, q3);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].knowledgePointId").value(kp1))
                .andExpect(jsonPath("$[0].practiceEvidenceCount").value(2))
                .andExpect(jsonPath("$[1].knowledgePointId").value(kp2))
                .andExpect(jsonPath("$[1].practiceEvidenceCount").value(2));
    }

    /** (11) review evidence count persists through recompute. */
    @Test
    void reviewEvidenceCountPersists() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);

        runPracticeCorrect(token, spaceId, q1);
        seedKpReviewRecord(spaceId, "biz-e2e-user-1", kpId,
                "2099-01-01 00:00:00.000000");
        // second submitted practice triggers recompute AFTER the review record
        runPracticeCorrect(token, spaceId, q1);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reviewEvidenceCount").value(1))
                .andExpect(jsonPath("$[0].practiceEvidenceCount").value(2))
                .andExpect(jsonPath("$[0].masteryScore").value(1.0));
    }

    /** (12) review completed_at participates in lastEvidenceAt (max of all three). */
    @Test
    void reviewCompletedAtParticipatesInLastEvidenceAt() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);

        runPracticeCorrect(token, spaceId, q1);
        seedKpReviewRecord(spaceId, "biz-e2e-user-1", kpId,
                "2099-01-01 00:00:00.000000");
        runPracticeCorrect(token, spaceId, q1);

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery",
                        spaceId, kpId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastEvidenceAt").value("2099-01-01T00:00:00"));
    }

    /** (13) owner list is weakest-first. */
    @Test
    void ownerListWeakestFirst() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long weak = insertFixturePoint(spaceId, "弱");
        Long strong = insertFixturePoint(spaceId, "强");
        String token = tokenFor("biz-e2e-user-1");
        Long qw1 = createPublishedQuestion(token, spaceId, "qw1", weak);
        Long qw2 = createPublishedQuestion(token, spaceId, "qw2", weak);
        Long qs = createPublishedQuestion(token, spaceId, "qs", strong);

        // weak: 1 correct + 1 wrong = 0.5; strong: 1 correct = 1.0
        Long sessionId = startPractice(token, spaceId, qw1, qw2);
        List<Long> slots = slotIdsOf(token, spaceId, sessionId);
        answerPractice(token, spaceId, sessionId, slots.get(0), "A"); // correct
        answerPractice(token, spaceId, sessionId, slots.get(1), "B"); // wrong
        finishPractice(token, spaceId, sessionId);
        runPracticeCorrect(token, spaceId, qs);

        mockMvc.perform(get(MASTERY, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].knowledgePointId").value(weak))
                .andExpect(jsonPath("$[0].masteryScore").value(0.5))
                .andExpect(jsonPath("$[1].knowledgePointId").value(strong))
                .andExpect(jsonPath("$[1].masteryScore").value(1.0));
    }

    /** (14) detail for same owner + same space → 200 with full typed row. */
    @Test
    void detailSameOwner() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeCorrect(token, spaceId, q1);

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery",
                        spaceId, kpId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledgePointId").value(kpId))
                .andExpect(jsonPath("$.masteryScore").value(1.0))
                .andExpect(jsonPath("$.practiceEvidenceCount").value(1))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    /** (15) other user / wrong space / wrong KP → 404 (anti-probing). */
    @Test
    void isolationIs404() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        Long kpId = insertFixturePoint(space1, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, space1, "q1", kpId);
        runPracticeCorrect(token, space1, q1);

        // other owner on space1 → 404 (space not owned)
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery",
                        space1, kpId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        // same owner, wrong space → 404
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery",
                        space2, kpId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // same owner, wrong KP → 404
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery",
                        space1, 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // list on a foreign space → 404
        mockMvc.perform(get(MASTERY, space1)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        // anonymous → 401
        mockMvc.perform(get(MASTERY, space1))
                .andExpect(status().isUnauthorized());
    }

    /** (16) API never accepts a client-submitted mastery score: no write paths. */
    @Test
    void clientCannotSubmitMasteryScore() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        mockMvc.perform(post(MASTERY, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masteryScore\":0.99}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery",
                        spaceId, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masteryScore\":0.99}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isMethodNotAllowed());
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
                    "Refusing to run MasteryVerticalSliceIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
