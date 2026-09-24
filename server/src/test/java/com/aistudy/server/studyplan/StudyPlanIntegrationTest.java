package com.aistudy.server.studyplan;

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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-016 — StudyPlan / StudyTask vertical slice integration
 * test.
 *
 * <p>Real MySQL ({@code flyway-it}), MockMvc + real JWT, schema guard
 * against {@code aistudy_flyway_test}. Deterministic generation is
 * driven end-to-end through practice (wrong answers → review tasks +
 * mastery) and exams (diagnosis reasons); plan lifecycle
 * ACTIVE → COMPLETED and task completion contracts are asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudyPlanIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1", "biz-e2e-user-2");

    private static final String PLAN = "/api/v1/spaces/{spaceId}/study-plan";
    private static final String GENERATE = "/api/v1/spaces/{spaceId}/study-plan/generate";
    private static final String COMPLETE =
            "/api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/complete";
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

    /** Creates + publishes a question (SINGLE_CHOICE, correct A, optional KP links). */
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

    /** Creates + publishes a SHORT_ANSWER question linked to a KP. */
    private Long createPublishedShortAnswer(String token, Long spaceId, String stem, Long kpId)
            throws Exception {
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

    /** Practice run: answers each slot with the given key ("TEXT" for short answer). */
    private Long runPractice(String token, Long spaceId, Long[] questionIds, String[] keys,
                             boolean finish) throws Exception {
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

        List<Long> slots = slotIdsOf(token, spaceId, sessionId);
        for (int i = 0; i < slots.size(); i++) {
            if (keys[i] == null) {
                continue;
            }
            String payload = "TEXT".equals(keys[i])
                    ? "{\"textAnswer\":\"ACID\"}"
                    : "{\"selectedOptionKeys\":[\"" + keys[i] + "\"]}";
            mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"practiceSessionQuestionId\":" + slots.get(i) + ","
                                    + "\"answer\":" + payload + "}")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        if (finish) {
            mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        return sessionId;
    }

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
        assertTrue(!ids.isEmpty(), "session detail must expose slot ids: " + detail);
        return ids;
    }

    /** Wrong-answer practice (creates review task + mastery row for the KP). */
    private void runPracticeWrong(String token, Long spaceId, Long q1, Long kpId)
            throws Exception {
        Long sessionId = runPractice(token, spaceId, new Long[]{q1}, new String[]{"B"}, true);
        assertNotNull(sessionId);
    }

    /** Exam flow for diagnosis reasons. */
    private Long runExamCorrect(String token, Long spaceId, Long q1, Long kpId) throws Exception {
        String body = mockMvc.perform(post(EXAMS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"exam\",\"durationMinutes\":60,"
                                + "\"questions\":[{\"questionId\":" + q1 + ",\"score\":3}]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long examId = extractJsonLong(body, "id");
        mockMvc.perform(post(EXAMS + "/{examId}/publish", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String attemptBody = mockMvc.perform(post(EXAMS + "/{examId}/sessions", spaceId, examId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long attemptId = extractJsonLong(attemptBody, "id");
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/start", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String detail = mockMvc.perform(get(ATTEMPTS + "/{attemptId}", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String marker = "\"examQuestionId\":";
        int start = detail.indexOf(marker);
        int valueStart = start + marker.length();
        int end = detail.indexOf(',', valueStart);
        if (end < 0) {
            end = detail.indexOf('}', valueStart);
        }
        Long slotId = Long.parseLong(detail.substring(valueStart, end).trim());
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/answers", spaceId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"A\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(ATTEMPTS + "/{attemptId}/submit", spaceId, attemptId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return attemptId;
    }

    private String generate(String token, Long spaceId, String body) throws Exception {
        return mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    private Long generateOk(String token, Long spaceId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return extractJsonLong(result.getResponse().getContentAsString(), "id");
    }

    private void completeTaskOk(String token, Long spaceId, Long taskId) throws Exception {
        mockMvc.perform(post(COMPLETE, spaceId, taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedAt").exists());
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

    /** (1) empty evidence → no candidates → 409. */
    @Test
    void generateWithNoEvidenceIs409() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        createPublishedQuestion(token, spaceId, "q1", kpId); // no answers at all

        mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plan\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (2) pending review tasks come first (REVIEW / QUESTION). */
    @Test
    void reviewTasksPrioritized() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId);

        String body = generate(token, spaceId, "{\"name\":\"plan\"}");
        assertTrue(body.contains("\"status\":\"ACTIVE\""), body);

        mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].taskType").value("REVIEW"))
                .andExpect(jsonPath("$.tasks[0].targetType").value("QUESTION"))
                .andExpect(jsonPath("$.tasks[0].targetId").value(q1))
                .andExpect(jsonPath("$.tasks[0].dueAt").exists())
                // weakest mastery point follows the review task
                .andExpect(jsonPath("$.tasks[1].taskType").value("PRACTICE"))
                .andExpect(jsonPath("$.tasks[1].targetType").value("KNOWLEDGE_POINT"))
                .andExpect(jsonPath("$.tasks[1].targetId").value(kpId));
    }

    /** (3) weakest mastery point first (score ASC). */
    @Test
    void weakestMasteryFirst() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long weak = insertFixturePoint(spaceId, "弱");
        Long strong = insertFixturePoint(spaceId, "强");
        String token = tokenFor("biz-e2e-user-1");
        Long qw1 = createPublishedQuestion(token, spaceId, "qw1", weak);
        Long qw2 = createPublishedQuestion(token, spaceId, "qw2", weak);
        Long qs = createPublishedQuestion(token, spaceId, "qs", strong);

        // weak: 1/2 = 0.5 (one correct, one wrong); strong: 1/1 = 1.0
        runPractice(token, spaceId, new Long[]{qw1, qw2}, new String[]{"A", "B"}, true);
        runPractice(token, spaceId, new Long[]{qs}, new String[]{"A"}, true);

        // POST generate creates the current plan; GET only reads it
        generateOk(token, spaceId, "{\"name\":\"plan\"}");

        // tasks: [REVIEW qw2 (wrong answer), PRACTICE weak, PRACTICE strong]
        mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].taskType").value("REVIEW"))
                .andExpect(jsonPath("$.tasks[1].targetId").value(weak))
                .andExpect(jsonPath("$.tasks[1].taskType").value("PRACTICE"))
                .andExpect(jsonPath("$.tasks[2].targetId").value(strong));
    }

    /** (4) deterministic: completing a plan and regenerating yields the same tasks. */
    @Test
    void stableDeterministicOrdering() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId);

        Long plan1 = generateOk(token, spaceId, "{\"name\":\"p1\"}");
        String get1 = mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String targets1 = extractTargets(get1);
        List<Long> plan1TaskIds = jdbcTemplate.queryForList(
                "SELECT id FROM study_task WHERE study_plan_id = ? ORDER BY id ASC",
                Long.class, plan1);
        assertTrue(plan1TaskIds.size() >= 2, "plan1 must have generated tasks");

        // complete every task of plan1 → plan becomes COMPLETED
        for (Long taskId : plan1TaskIds) {
            completeTaskOk(token, spaceId, taskId);
        }
        mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // regenerate → identical deterministic task sequence
        Long plan2 = generateOk(token, spaceId, "{\"name\":\"p2\"}");
        String get2 = mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(targets1, extractTargets(get2),
                "regeneration must be deterministic");
        assertTrue(plan2 > plan1, "a NEW plan row must be created");
    }

    /** (5) dailyItemLimit bounds the generated task count. */
    @Test
    void dailyLimitBounded() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId); // review task + mastery row

        mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plan\",\"dailyItemLimit\":1}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tasks.length()").value(1));
        // out-of-range limit → 400
        mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plan\",\"dailyItemLimit\":0}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()); // @Min(1) validation
        mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plan\",\"dailyItemLimit\":51}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()); // @Max(50) validation
    }

    /** (6) no duplicate tasks for the same logical target within one plan. */
    @Test
    void noDuplicateTasksPerTarget() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        // practice wrong → QUESTION review task + mastery row (0.0) for kpId
        runPracticeWrong(token, spaceId, q1, kpId);
        // seed a PENDING KNOWLEDGE_POINT review task for the SAME kp
        jdbcTemplate.update(
                "INSERT INTO review_task "
                        + "(user_subject, space_id, target_type, target_id, reason, due_at, "
                        + "priority, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'KNOWLEDGE_POINT', ?, 'KP_REVIEW', "
                        + "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY), "
                        + "'HIGH', 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                "biz-e2e-user-1", spaceId, kpId);

        mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plan\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                // exactly ONE task for kpId: the REVIEW one, no PRACTICE/LEARN duplicate.
                // NOTE: jsonPath(...).length() on a filtered selection resolves to the
                // MATCHING OBJECT'S property count (StudyTaskView has 11 properties),
                // not the number of matches — so count the collection with hasSize.
                .andExpect(jsonPath("$.tasks[?(@.targetType=='KNOWLEDGE_POINT' && @.targetId==" + kpId + ")]",
                        hasSize(1)))
                .andExpect(jsonPath(
                        "$.tasks[?(@.targetType=='KNOWLEDGE_POINT' && @.targetId==" + kpId + ")].taskType",
                        contains("REVIEW")));
    }

    /** (7) date order validation → 400. */
    @Test
    void dateValidation() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId);

        mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plan\",\"startDate\":\"2026-09-10T00:00:00\","
                                + "\"endDate\":\"2026-09-01T00:00:00\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** (8) generating while an ACTIVE plan exists → 409. */
    @Test
    void activePlanConflictIs409() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId);

        generateOk(token, spaceId, "{\"name\":\"p1\"}");
        mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p2\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /**
     * (9) GET current plan: 200 with tasks; 404 when no plan exists.
     *
     * <p>Also pins the no-side-effect contract: GET never generates a plan.
     * On a brand-new owned space that never called POST /study-plan/generate
     * the answer is 404, not a lazily-created plan.
     */
    @Test
    void getCurrentPlan() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);

        // evidence exists (a published question) but no plan was ever generated
        // → GET must not create one
        mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        runPracticeWrong(token, spaceId, q1, kpId);
        Long planId = generateOk(token, spaceId, "{\"name\":\"plan\"}");
        mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId))
                .andExpect(jsonPath("$.name").value("plan"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tasks.length()").value(2));
    }

    /** (10) task completion: TODO → DONE with completedAt. */
    @Test
    void taskCompletion() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId);
        Long planId = generateOk(token, spaceId, "{\"name\":\"plan\"}");

        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM study_task WHERE study_plan_id = ? AND status = 'TODO' "
                        + "ORDER BY id ASC LIMIT 1", Long.class, planId);
        mockMvc.perform(post(COMPLETE, spaceId, taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    /** (11) repeated completion is idempotent (200, same completedAt). */
    @Test
    void repeatedCompletionIdempotent() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId);
        Long planId = generateOk(token, spaceId, "{\"name\":\"plan\"}");

        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM study_task WHERE study_plan_id = ? ORDER BY id ASC LIMIT 1",
                Long.class, planId);
        String first = mockMvc.perform(post(COMPLETE, spaceId, taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String completedAt = first.replaceFirst(".*\"completedAt\":\"([^\"]*)\".*", "$1");

        String second = mockMvc.perform(post(COMPLETE, spaceId, taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(second.contains("\"completedAt\":\"" + completedAt + "\""),
                "idempotent completion must not refresh completedAt: " + second);
    }

    /** (12) all tasks done → plan COMPLETED. */
    @Test
    void allTasksDoneCompletesPlan() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId);
        Long planId = generateOk(token, spaceId, "{\"name\":\"plan\"}");

        List<Long> taskIds = jdbcTemplate.queryForList(
                "SELECT id FROM study_task WHERE study_plan_id = ? ORDER BY id ASC",
                Long.class, planId);
        assertTrue(taskIds.size() >= 2);
        for (Long taskId : taskIds) {
            completeTaskOk(token, spaceId, taskId);
        }

        mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        // a COMPLETED plan frees generation for a new plan
        mockMvc.perform(post(GENERATE, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p2\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    /** (13) other user / wrong space / unknown task → 404. */
    @Test
    void isolationIs404() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        Long kpId = insertFixturePoint(space1, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, space1, "q1", kpId);
        runPracticeWrong(token, space1, q1, kpId);
        Long planId = generateOk(token, space1, "{\"name\":\"plan\"}");
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM study_task WHERE study_plan_id = ? ORDER BY id ASC LIMIT 1",
                Long.class, planId);

        // other owner → 404 (get + complete + generate)
        mockMvc.perform(get(PLAN, space1)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(COMPLETE, space1, taskId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(GENERATE, space1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        // wrong space → 404
        mockMvc.perform(get(PLAN, space2)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(COMPLETE, space2, taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // unknown task → 404
        mockMvc.perform(post(COMPLETE, space1, 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // anonymous → 401
        mockMvc.perform(get(PLAN, space1))
                .andExpect(status().isUnauthorized());
    }

    /** (14) SKIPPED task cannot be completed → 409. */
    @Test
    void skippedTaskCannotComplete() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        runPracticeWrong(token, spaceId, q1, kpId);
        Long planId = generateOk(token, spaceId, "{\"name\":\"plan\"}");
        jdbcTemplate.update(
                "UPDATE study_task SET status = 'SKIPPED' WHERE study_plan_id = ? "
                        + "ORDER BY id ASC LIMIT 1", planId);
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM study_task WHERE study_plan_id = ? AND status = 'SKIPPED' "
                        + "ORDER BY id ASC LIMIT 1", Long.class, planId);

        mockMvc.perform(post(COMPLETE, spaceId, taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (15) zero-evidence mastery → LEARN task; evidenced → PRACTICE task. */
    @Test
    void learnVsPracticeTaskTypes() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long learnKp = insertFixturePoint(spaceId, "学");
        Long practiceKp = insertFixturePoint(spaceId, "练");
        String token = tokenFor("biz-e2e-user-1");
        Long qs = createPublishedShortAnswer(token, spaceId, "qs", learnKp);
        Long qp = createPublishedQuestion(token, spaceId, "qp", practiceKp);

        // learnKp: SHORT_ANSWER-only practice → mastery row with 0 graded evidence
        runPractice(token, spaceId, new Long[]{qs}, new String[]{"TEXT"}, true);
        // practiceKp: wrong answer → mastery row with 1 graded evidence
        runPractice(token, spaceId, new Long[]{qp}, new String[]{"B"}, true);

        // GET has no generation side effect — POST create the plan first
        generateOk(token, spaceId, "{\"name\":\"plan\"}");

        mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.tasks[?(@.targetType=='KNOWLEDGE_POINT' && @.targetId==" + learnKp + ")].taskType",
                        contains("LEARN")))
                .andExpect(jsonPath(
                        "$.tasks[?(@.targetType=='KNOWLEDGE_POINT' && @.targetId==" + practiceKp + ")].taskType",
                        contains("PRACTICE")));
    }

    /** (16) latest ExamDiagnosis feeds the generated task reason. */
    @Test
    void diagnosisFeedsTaskReason() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1", kpId);
        // exam: 1/1 correct → mastery 1.0 + diagnosis KP item accuracy 1.0
        runExamCorrect(token, spaceId, q1, kpId);
        // one wrong practice answer so the KP is still weak enough to be listed
        Long q2 = createPublishedQuestion(token, spaceId, "q2", kpId);
        runPractice(token, spaceId, new Long[]{q2}, new String[]{"B"}, true);

        // GET has no generation side effect — POST create the plan first
        generateOk(token, spaceId, "{\"name\":\"plan\"}");

        mockMvc.perform(get(PLAN, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.tasks[?(@.targetType=='KNOWLEDGE_POINT' && @.targetId==" + kpId + ")].reason",
                        contains(
                                "Exam diagnosis: accuracy 1.00 (score 3/3)")));
    }

    // ==================== helpers ====================

    private static String extractTargets(String planBody) {
        StringBuilder sb = new StringBuilder();
        int from = 0;
        while (true) {
            int start = planBody.indexOf("\"targetId\":", from);
            if (start < 0) {
                break;
            }
            int valueStart = start + "\"targetId\":".length();
            int end = planBody.indexOf(',', valueStart);
            if (end < 0) {
                end = planBody.indexOf('}', valueStart);
            }
            sb.append(planBody, valueStart, end).append(';');
            from = end;
        }
        return sb.toString();
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
                    "Refusing to run StudyPlanIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
