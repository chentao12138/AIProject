package com.aistudy.server.exam;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.testsupport.OwnedSpaceReset;
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
 * BUSINESS-012 — Exam definition vertical slice integration test
 * (real MySQL, {@code flyway-it}).
 *
 * <p>Covers: create with scored composition (totalScore = sum); 400
 * (duplicate question / score 0 / empty composition); cross-space and
 * non-owner 404; publish + idempotent re-publish; paper v1 frozen at
 * create and flipped to PUBLISHED at publish; composition views carry
 * NO answer data; snapshots frozen in DB. Exam attempt/answer/result
 * are covered by BUSINESS-013 in this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExamVerticalSliceIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1", "biz-e2e-user-2");

    private static final String EXAMS = "/api/v1/spaces/{spaceId}/exams";
    private static final String QUESTIONS = "/api/v1/spaces/{spaceId}/questions";

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

    private String createExam(String token, Long spaceId, String body) throws Exception {
        return mockMvc.perform(post(EXAMS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    // ==================== create ====================

    /** (1) create → 201, totalScore = sum, paper v1 DRAFT, safe composition. */
    @Test
    void createExamWithScoredComposition() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long q2 = createPublishedQuestion(token, spaceId, "q2");

        String body = createExam(token, spaceId,
                "{\"title\":\"第1章测试\",\"description\":\"d\",\"timeLimitMinutes\":30,"
                        + "\"questions\":[{\"questionId\":" + q1 + ",\"score\":2},"
                        + "{\"questionId\":" + q2 + ",\"score\":3}]}");

        assertTrue(body.contains("\"totalScore\":5"), body);
        assertTrue(body.contains("\"status\":\"DRAFT\""), body);
        assertTrue(body.contains("\"paperVersion\":1"), body);
        assertTrue(body.contains("\"examType\":\"STANDARD\""), body);
        assertTrue(body.contains("\"timeLimitMinutes\":30"), body);
        assertTrue(body.contains("\"questions\":"), body);
        assertTrue(body.contains("\"score\":2"), body);
        // SAFE view: no answer leakage in the composition
        assertFalse(body.contains("correctOptionKey"), body);
        assertFalse(body.contains("answerData"), body);
        assertFalse(body.contains("isCorrect"), body);
    }

    /** (2) duplicate question → 400; score 0 → 400; empty composition → 400. */
    @Test
    void invalidCompositionsAreRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");

        mockMvc.perform(post(EXAMS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"questions\":[{\"questionId\":" + q1
                                + ",\"score\":1},{\"questionId\":" + q1 + ",\"score\":1}]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(EXAMS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"questions\":[{\"questionId\":" + q1
                                + ",\"score\":0}]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(EXAMS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"questions\":[]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** (3) cross-space / draft / non-owner question → whole create 404. */
    @Test
    void invalidQuestionRejectsWholeCreate() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        String token = tokenFor("biz-e2e-user-1");
        Long qOther = createPublishedQuestion(token, space2, "异空间题");
        // draft question in space1
        MvcResult draft = mockMvc.perform(post(QUESTIONS, space1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"draft\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}],\"correctOptionKey\":\"A\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long qDraft = extractJsonLong(draft.getResponse().getContentAsString(), "id");

        // cross-space
        mockMvc.perform(post(EXAMS, space1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"questions\":[{\"questionId\":" + qOther
                                + ",\"score\":1}]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // draft question
        mockMvc.perform(post(EXAMS, space1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"questions\":[{\"questionId\":" + qDraft
                                + ",\"score\":1}]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // non-owner
        mockMvc.perform(post(EXAMS, space1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"questions\":[{\"questionId\":" + qDraft
                                + ",\"score\":1}]}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        Integer exams = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam WHERE space_id = ?", Integer.class, space1);
        assertEquals(0, exams, "failed creates must leave zero exams");
    }

    // ==================== publish ====================

    /** (4) publish: exam + paper → PUBLISHED; republish idempotent. */
    @Test
    void publishAndRepublishIsIdempotent() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createExam(token, spaceId,
                "{\"title\":\"t\",\"questions\":[{\"questionId\":" + q1 + ",\"score\":1}]}");
        Long examId = extractJsonLong(body, "id");

        String published = mockMvc.perform(post(EXAMS + "/{examId}/publish", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        String publishedAt = published.replaceFirst(".*\"publishedAt\":\"([^\"]*)\".*", "$1");

        String republished = mockMvc.perform(post(EXAMS + "/{examId}/publish", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(republished.contains("\"publishedAt\":\"" + publishedAt + "\""),
                "republish must not refresh publishedAt");

        Integer papers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_paper WHERE exam_id = ?", Integer.class, examId);
        assertEquals(1, papers, "republish must not create a second paper");
        String paperStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM exam_paper WHERE exam_id = ?", String.class, examId);
        assertEquals("PUBLISHED", paperStatus);
    }

    /** (5) snapshot frozen in DB; detail under cross-space/other owner → 404. */
    @Test
    void snapshotFrozenAndDetailIsolated() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, space1, "q1");
        String body = createExam(token, space1,
                "{\"title\":\"t\",\"questions\":[{\"questionId\":" + q1 + ",\"score\":1}]}");
        Long examId = extractJsonLong(body, "id");

        String snapshot = jdbcTemplate.queryForObject(
                "SELECT question_snapshot_json FROM exam_question WHERE exam_paper_id = "
                        + "(SELECT id FROM exam_paper WHERE exam_id = ? LIMIT 1) LIMIT 1",
                String.class, examId);
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("answerData"), "snapshot must freeze answer data: " + snapshot);

        mockMvc.perform(get(EXAMS + "/{examId}", space2, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(EXAMS + "/{examId}", space1, examId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(EXAMS + "/{examId}", space1, examId))
                .andExpect(status().isUnauthorized());
    }

    // ==================== BUSINESS-013 attempt / answer / result ====================

    private static final String ATTEMPTS = "/api/v1/spaces/{spaceId}/exam-attempts";

    private Long createExamAndPublish(String token, Long spaceId, Long q1, int score)
            throws Exception {
        String body = createExam(token, spaceId,
                "{\"title\":\"exam\",\"timeLimitMinutes\":60,"
                        + "\"questions\":[{\"questionId\":" + q1 + ",\"score\":" + score + "}]}");
        Long examId = extractJsonLong(body, "id");
        mockMvc.perform(post(EXAMS + "/{examId}/publish", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return examId;
    }

    private Long slotIdOfAttempt(String token, Long spaceId, Long attemptId) throws Exception {
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

    private Long startAttempt(String token, Long spaceId, Long examId) throws Exception {
        String body = mockMvc.perform(post(EXAMS + "/{examId}/sessions", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long attemptId = extractJsonLong(body, "id");
        // Generic start: deadlineAt is only present for TIMED exams
        // (exam.timeLimitMinutes != null), so it must not be asserted here —
        // see startPublishedExamComputesDeadline for the timed contract and
        // wrongAndUnansweredScoring for the untimed one.
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/start", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").exists());
        return attemptId;
    }

    /** (6) create+start published exam; deadline = start + duration. */
    @Test
    void startPublishedExamComputesDeadline() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long examId = createExamAndPublish(token, spaceId, q1, 5);

        String body = mockMvc.perform(post(EXAMS + "/{examId}/sessions", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        Long attemptId = extractJsonLong(body, "id");

        String started = mockMvc.perform(post(ATTEMPTS + "/{attemptId}/start", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadlineAt").exists())
                .andReturn().getResponse().getContentAsString();
        // deadline ≈ now + 60min
        String deadline = started.replaceFirst(".*\"deadlineAt\":\"([^\"]*)\".*", "$1");
        java.time.LocalDateTime due = java.time.LocalDateTime.parse(deadline.replace(' ', 'T'));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        assertTrue(due.isAfter(now.plusMinutes(55)) && due.isBefore(now.plusMinutes(65)),
                "deadline must be ~+60min: " + due);
        // double start → 409
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/start", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (7) draft exam cannot start → 409; unknown exam → 404. */
    @Test
    void draftExamCannotStart() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        String body = createExam(token, spaceId,
                "{\"title\":\"draft exam\",\"questions\":[{\"questionId\":" + q1 + ",\"score\":1}]}");
        Long examId = extractJsonLong(body, "id");

        mockMvc.perform(post(EXAMS + "/{examId}/sessions", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(post(EXAMS + "/{examId}/sessions", spaceId, 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (8) answer graded silently: no leak pre-submit; result reveals. */
    @Test
    void answerGradedSilentlyAndResultReveals() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long examId = createExamAndPublish(token, spaceId, q1, 5);
        Long attemptId = startAttempt(token, spaceId, examId);
        Long slotId = slotIdOfAttempt(token, spaceId, attemptId);

        // correct answer → stored, but NO correctness in response
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examQuestionId").value(slotId))
                .andExpect(jsonPath("$.gradingStatus").value("GRADED"))
                .andExpect(jsonPath("$.isCorrect").doesNotExist())
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.correctAnswer").doesNotExist());

        // attempt view must not leak either
        String attemptView = mockMvc.perform(get(ATTEMPTS + "/{attemptId}", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(attemptView.contains("answerData"), attemptView);
        assertFalse(attemptView.contains("correctOptionKey"), attemptView);

        // submit → score = 5 (item score), correctness revealed
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(5))
                .andExpect(jsonPath("$.maxScore").value(5))
                .andExpect(jsonPath("$.correctCount").value(1))
                .andExpect(jsonPath("$.wrongCount").value(0))
                .andExpect(jsonPath("$.unansweredCount").value(0))
                .andExpect(jsonPath("$.items[0].isCorrect").value(true))
                .andExpect(jsonPath("$.items[0].correctAnswer").value("A"))
                .andExpect(jsonPath("$.items[0].score").value(5));

        // repeated submit → 409
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        // result endpoint after submit → 200 (idempotent read)
        mockMvc.perform(get(ATTEMPTS + "/{attemptId}/result", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(5));
        // result BEFORE submit → 409 (covered in test 10)
    }

    /** (9) wrong answer → item score 0; unanswered → counted. */
    @Test
    void wrongAndUnansweredScoring() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long q2 = createPublishedQuestion(token, spaceId, "q2");
        String body = createExam(token, spaceId,
                "{\"title\":\"exam2\",\"questions\":[{\"questionId\":" + q1 + ",\"score\":2},"
                        + "{\"questionId\":" + q2 + ",\"score\":3}]}");
        Long examId = extractJsonLong(body, "id");
        mockMvc.perform(post(EXAMS + "/{examId}/publish", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long attemptId = startAttempt(token, spaceId, examId);

        // Untimed exam (no timeLimitMinutes): start succeeds and deadlineAt is
        // null — production deliberately does NOT invent a deadline for an
        // untimed paper.
        mockMvc.perform(get(ATTEMPTS + "/{attemptId}", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.deadlineAt").value(org.hamcrest.Matchers.nullValue()));

        String detail = mockMvc.perform(get(ATTEMPTS + "/{attemptId}", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String marker = "\"examQuestionId\":";
        int idx = detail.indexOf(marker);
        int valueStart = idx + marker.length();
        int end = detail.indexOf(',', valueStart);
        long slot1 = Long.parseLong(detail.substring(valueStart, end).trim());
        int idx2 = detail.indexOf(marker, idx + 1);
        int valueStart2 = detail.indexOf(':', idx2) + 1;
        int end2 = detail.indexOf(',', valueStart2);
        long slot2 = Long.parseLong(detail.substring(valueStart2, end2).trim());

        // wrong answer on slot1 (score 2), no answer on slot2 (score 3)
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":" + slot1 + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"B\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.maxScore").value(5))
                .andExpect(jsonPath("$.correctCount").value(0))
                .andExpect(jsonPath("$.wrongCount").value(1))
                .andExpect(jsonPath("$.unansweredCount").value(1));
    }

    /** (10) result before submit → 409; answer for foreign slot → 404. */
    @Test
    void resultBeforeSubmitAndForeignSlotRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long examId = createExamAndPublish(token, spaceId, q1, 5);
        Long attemptId = startAttempt(token, spaceId, examId);

        mockMvc.perform(get(ATTEMPTS + "/{attemptId}/result", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":999999,"
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // answer on a NOT_STARTED attempt → 409 (not IN_PROGRESS yet)
        String fresh = mockMvc.perform(post(EXAMS + "/{examId}/sessions", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long freshId = extractJsonLong(fresh, "id");
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, freshId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":999999,"
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (11) deadline exceeded → 409 on submit. */
    @Test
    void deadlineExceededBlocksSubmit() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        Long examId = createExamAndPublish(token, spaceId, q1, 5);
        Long attemptId = startAttempt(token, spaceId, examId);

        // force expiry (server clock authority via DB deadline)
        jdbcTemplate.update(
                "UPDATE exam_attempt SET deadline_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE) "
                        + "WHERE id = ?", attemptId);

        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":999999,"
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (12) isolation: cross-space attempt → 404; non-owner → 404. */
    @Test
    void attemptIsolation() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, space1, "q1");
        Long examId = createExamAndPublish(token, space1, q1, 5);
        Long attemptId = startAttempt(token, space1, examId);

        mockMvc.perform(get(ATTEMPTS + "/{attemptId}", space2, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(ATTEMPTS + "/{attemptId}", space1, attemptId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", space2, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(EXAMS + "/{examId}/sessions", space1, examId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(ATTEMPTS + "/{attemptId}", space1, attemptId))
                .andExpect(status().isUnauthorized());
    }

    // ==================== cleanup ====================

    private void cleanBizTestRows() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, BIZ_TEST_USERS);
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run ExamVerticalSliceIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
