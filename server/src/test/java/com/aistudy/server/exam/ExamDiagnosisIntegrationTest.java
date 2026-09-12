package com.aistudy.server.exam;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-015 — ExamDiagnosis vertical slice integration test.
 *
 * <p>Real MySQL ({@code flyway-it}), MockMvc + real JWT, schema guard
 * against {@code aistudy_flyway_test}. Diagnosis is generated
 * deterministically inside the exam submit transaction; this suite
 * drives the full exam flow and asserts the persisted dimension
 * aggregates (KNOWLEDGE_POINT + QUESTION_TYPE).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExamDiagnosisIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1", "biz-e2e-user-2");

    private static final String EXAMS = "/api/v1/spaces/{spaceId}/exams";
    private static final String ATTEMPTS = "/api/v1/spaces/{spaceId}/exam-attempts";
    private static final String QUESTIONS = "/api/v1/spaces/{spaceId}/questions";
    private static final String DIAGNOSIS =
            "/api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/diagnosis";

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

    /** Creates + publishes a question; type SINGLE_CHOICE (correct A) or SHORT_ANSWER. */
    private Long createPublishedQuestion(String token, Long spaceId, String stem,
                                         String type, Long... kpIds) throws Exception {
        String kpJson = kpIds.length == 0 ? "" : ",\"knowledgePointIds\":["
                + String.join(",", java.util.Arrays.stream(kpIds)
                .map(String::valueOf).toList()) + "]";
        String bodyJson;
        if ("SHORT_ANSWER".equals(type)) {
            bodyJson = "{\"questionType\":\"SHORT_ANSWER\",\"stem\":\"" + stem + "\","
                    + "\"referenceAnswer\":\"ACID\"" + kpJson + "}";
        } else {
            bodyJson = "{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"" + stem + "\","
                    + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                    + "{\"optionKey\":\"B\",\"content\":\"b\"}],"
                    + "\"correctOptionKey\":\"A\"" + kpJson + "}";
        }
        MvcResult result = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long id = extractJsonLong(result.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return id;
    }

    /** Creates + publishes an exam over the given (questionId, score) pairs. */
    private Long createExamAndPublish(String token, Long spaceId, Long[] questionIds,
                                      Integer[] scores) throws Exception {
        StringBuilder sb = new StringBuilder("{\"title\":\"exam\",\"durationMinutes\":60,"
                + "\"questions\":[");
        for (int i = 0; i < questionIds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"questionId\":").append(questionIds[i])
                    .append(",\"score\":").append(scores[i]).append('}');
        }
        sb.append("]}");
        String body = mockMvc.perform(post(EXAMS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.toString())
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

    private List<Long> slotIdsOf(String token, Long spaceId, Long attemptId) throws Exception {
        String detail = mockMvc.perform(get(ATTEMPTS + "/{attemptId}", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = new java.util.ArrayList<>();
        String marker = "\"examQuestionId\":";
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
        assertTrue(!ids.isEmpty(), "attempt detail must expose slot ids: " + detail);
        return ids;
    }

    private void answerExam(String token, Long spaceId, Long attemptId, Long slotId,
                            String key) throws Exception {
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"" + key + "\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void answerExamText(String token, Long spaceId, Long attemptId, Long slotId)
            throws Exception {
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":" + slotId + ","
                                + "\"answer\":{\"textAnswer\":\"ACID\"}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void submitExam(String token, Long spaceId, Long attemptId) throws Exception {
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** Full flow: create exam over (question,score) pairs, start, answer, submit. */
    private Long runExam(String token, Long spaceId, Long[] questionIds, Integer[] scores,
                         String[] keys) throws Exception {
        Long examId = createExamAndPublish(token, spaceId, questionIds, scores);
        Long attemptId = startAttempt(token, spaceId, examId);
        List<Long> slots = slotIdsOf(token, spaceId, attemptId);
        for (int i = 0; i < slots.size(); i++) {
            String key = keys[i];
            if (key == null) {
                continue; // unanswered
            }
            if ("TEXT".equals(key)) {
                answerExamText(token, spaceId, attemptId, slots.get(i));
            } else {
                answerExam(token, spaceId, attemptId, slots.get(i), key);
            }
        }
        submitExam(token, spaceId, attemptId);
        return attemptId;
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

    // ==================== tests ====================

    /** (1) diagnosis auto-created on submit with both dimensions. */
    @Test
    void diagnosisAutoCreatedOnSubmit() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kpId);

        Long attemptId = runExam(token, spaceId,
                new Long[]{q1}, new Integer[]{5}, new String[]{"A"});

        mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examAttemptId").value(attemptId))
                .andExpect(jsonPath("$.summary").value("Exam score 5/5"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].dimensionType").value("KNOWLEDGE_POINT"))
                .andExpect(jsonPath("$.items[0].dimensionId").value(kpId))
                .andExpect(jsonPath("$.items[0].label").value("点1"))
                .andExpect(jsonPath("$.items[0].score").value(5))
                .andExpect(jsonPath("$.items[0].maxScore").value(5))
                .andExpect(jsonPath("$.items[0].accuracy").value(1.0))
                .andExpect(jsonPath("$.items[0].evidenceCount").value(1))
                .andExpect(jsonPath("$.items[1].dimensionType").value("QUESTION_TYPE"))
                .andExpect(jsonPath("$.items[1].dimensionId").doesNotExist())
                .andExpect(jsonPath("$.items[1].label").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.items[1].score").value(5))
                .andExpect(jsonPath("$.items[1].maxScore").value(5));
    }

    /** (2) KP + question-type aggregation over multiple items. */
    @Test
    void kpAndTypeAggregation() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kpId);
        Long q2 = createPublishedQuestion(token, spaceId, "q2", "SINGLE_CHOICE", kpId);

        // q1 correct (5/5), q2 wrong (0/3) → KP: 5/8, accuracy 0.625
        Long attemptId = runExam(token, spaceId,
                new Long[]{q1, q2}, new Integer[]{5, 3}, new String[]{"A", "B"});

        mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].dimensionType").value("KNOWLEDGE_POINT"))
                .andExpect(jsonPath("$.items[0].score").value(5))
                .andExpect(jsonPath("$.items[0].maxScore").value(8))
                .andExpect(jsonPath("$.items[0].accuracy").value(0.625))
                .andExpect(jsonPath("$.items[0].evidenceCount").value(2))
                .andExpect(jsonPath("$.items[1].dimensionType").value("QUESTION_TYPE"))
                .andExpect(jsonPath("$.items[1].label").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.items[1].score").value(5))
                .andExpect(jsonPath("$.items[1].maxScore").value(8))
                .andExpect(jsonPath("$.items[1].accuracy").value(0.625))
                .andExpect(jsonPath("$.items[1].evidenceCount").value(2));
    }

    /** (3) multi-KP question: full attribution to every linked point. */
    @Test
    void multiKpQuestionFullAttribution() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kp1 = insertFixturePoint(spaceId, "点1");
        Long kp2 = insertFixturePoint(spaceId, "点2");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kp1, kp2);

        Long attemptId = runExam(token, spaceId,
                new Long[]{q1}, new Integer[]{4}, new String[]{"A"});

        mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].dimensionId").value(kp1))
                .andExpect(jsonPath("$.items[0].score").value(4))
                .andExpect(jsonPath("$.items[0].maxScore").value(4))
                .andExpect(jsonPath("$.items[0].accuracy").value(1.0))
                .andExpect(jsonPath("$.items[1].dimensionId").value(kp2))
                .andExpect(jsonPath("$.items[1].score").value(4))
                .andExpect(jsonPath("$.items[1].maxScore").value(4))
                .andExpect(jsonPath("$.items[1].accuracy").value(1.0));
    }

    /** (4) wrong + unanswered items count as evidence with zero score. */
    @Test
    void wrongAndUnansweredScoring() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kpId);
        Long q2 = createPublishedQuestion(token, spaceId, "q2", "SINGLE_CHOICE", kpId);

        // q1 wrong (0/2), q2 unanswered (0/1) → 0/3, evidenceCount 2
        Long attemptId = runExam(token, spaceId,
                new Long[]{q1, q2}, new Integer[]{2, 1}, new String[]{"B", null});

        mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Exam score 0/3"))
                .andExpect(jsonPath("$.items[0].score").value(0))
                .andExpect(jsonPath("$.items[0].maxScore").value(3))
                .andExpect(jsonPath("$.items[0].accuracy").value(0.0))
                .andExpect(jsonPath("$.items[0].evidenceCount").value(2));
    }

    /** (5) SHORT_ANSWER excluded — consistent with exam scoring. */
    @Test
    void shortAnswerExcluded() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long qs = createPublishedQuestion(token, spaceId, "qs", "SHORT_ANSWER", kpId);
        Long qo = createPublishedQuestion(token, spaceId, "qo", "SINGLE_CHOICE", kpId);

        Long attemptId = runExam(token, spaceId,
                new Long[]{qs, qo}, new Integer[]{1, 2}, new String[]{"TEXT", "A"});

        mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].dimensionType").value("KNOWLEDGE_POINT"))
                .andExpect(jsonPath("$.items[0].maxScore").value(2))
                .andExpect(jsonPath("$.items[0].evidenceCount").value(1))
                .andExpect(jsonPath("$.items[1].dimensionType").value("QUESTION_TYPE"))
                .andExpect(jsonPath("$.items[1].label").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.items[1].maxScore").value(2))
                .andExpect(jsonPath("$.items[1].evidenceCount").value(1));
    }

    /** (6) deterministic: identical inputs → identical diagnosis across attempts. */
    @Test
    void deterministicValues() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kpId);
        Long q2 = createPublishedQuestion(token, spaceId, "q2", "SINGLE_CHOICE", kpId);
        Long examId = createExamAndPublish(token, spaceId,
                new Long[]{q1, q2}, new Integer[]{5, 3});

        for (int round = 0; round < 2; round++) {
            Long attemptId = startAttempt(token, spaceId, examId);
            List<Long> slots = slotIdsOf(token, spaceId, attemptId);
            answerExam(token, spaceId, attemptId, slots.get(0), "A");
            answerExam(token, spaceId, attemptId, slots.get(1), "B");
            submitExam(token, spaceId, attemptId);

            mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].score").value(5))
                    .andExpect(jsonPath("$.items[0].maxScore").value(8))
                    .andExpect(jsonPath("$.items[0].accuracy").value(0.625))
                    .andExpect(jsonPath("$.items[1].label").value("SINGLE_CHOICE"))
                    .andExpect(jsonPath("$.items[1].score").value(5))
                    .andExpect(jsonPath("$.items[1].maxScore").value(8));
        }
    }

    /** (7) repeat submit → 409 and still exactly ONE diagnosis row. */
    @Test
    void repeatSubmit409AndNoDuplicateDiagnosis() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kpId);

        Long attemptId = runExam(token, spaceId,
                new Long[]{q1}, new Integer[]{5}, new String[]{"A"});
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_diagnosis WHERE exam_attempt_id = ?",
                Integer.class, attemptId);
        assertEquals(1, rows, "repeat submit must not create a second diagnosis");
    }

    /** (8) diagnosis before submit → 409. */
    @Test
    void preSubmitDiagnosisIs409() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kpId);
        Long examId = createExamAndPublish(token, spaceId, new Long[]{q1}, new Integer[]{5});
        Long attemptId = startAttempt(token, spaceId, examId);

        mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (9) submitted attempt with missing diagnosis → 409 (internal inconsistency). */
    @Test
    void missingDiagnosisIs409() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kpId);

        Long attemptId = runExam(token, spaceId,
                new Long[]{q1}, new Integer[]{5}, new String[]{"A"});
        jdbcTemplate.update("DELETE FROM exam_diagnosis_item WHERE exam_diagnosis_id IN "
                + "(SELECT id FROM exam_diagnosis WHERE exam_attempt_id = ?)", attemptId);
        jdbcTemplate.update("DELETE FROM exam_diagnosis WHERE exam_attempt_id = ?", attemptId);

        mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (10) other user / wrong space / unknown attempt → 404. */
    @Test
    void isolationIs404() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        Long kpId = insertFixturePoint(space1, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, space1, "q1", "SINGLE_CHOICE", kpId);
        Long attemptId = runExam(token, space1,
                new Long[]{q1}, new Integer[]{5}, new String[]{"A"});

        // other owner → 404
        mockMvc.perform(get(DIAGNOSIS, space1, attemptId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        // wrong space → 404
        mockMvc.perform(get(DIAGNOSIS, space2, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // unknown attempt → 404
        mockMvc.perform(get(DIAGNOSIS, space1, 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // anonymous → 401
        mockMvc.perform(get(DIAGNOSIS, space1, attemptId))
                .andExpect(status().isUnauthorized());
    }

    /** (11) typed response: no answer data leaks into the diagnosis. */
    @Test
    void diagnosisNeverLeaksAnswers() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", "SINGLE_CHOICE", kpId);
        Long attemptId = runExam(token, spaceId,
                new Long[]{q1}, new Integer[]{5}, new String[]{"A"});

        String body = mockMvc.perform(get(DIAGNOSIS, spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(!body.contains("answerData"), "diagnosis must not expose answerData: " + body);
        assertTrue(!body.contains("correctOptionKey"), "diagnosis must not expose correctOptionKey: " + body);
        assertTrue(!body.contains("isCorrect"), "diagnosis must not expose isCorrect: " + body);
        assertTrue(!body.contains("selectedOptionKeys"), "diagnosis must not expose selectedOptionKeys: " + body);
        assertTrue(!body.contains("textAnswer"), "diagnosis must not expose textAnswer: " + body);
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
                    "Refusing to run ExamDiagnosisIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
