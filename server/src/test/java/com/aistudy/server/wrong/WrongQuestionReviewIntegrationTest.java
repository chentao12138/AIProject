package com.aistudy.server.wrong;

import com.aistudy.server.spike.auth.SpikeJwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDateTime;
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
 * BUSINESS-011 — WrongQuestion + ReviewTask vertical slice integration
 * test (real MySQL, {@code flyway-it}).
 *
 * <p>Drives the FULL chain: publish question → practice session →
 * wrong answer → finish (auto wrong_question + review_task) → review
 * completion transitions (WRONG +1d / CORRECT +3d / consecutive
 * CORRECT → MASTERED) → lists with owner isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WrongQuestionReviewIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1", "biz-e2e-user-2");

    private static final String QUESTIONS = "/api/v1/spaces/{spaceId}/questions";
    private static final String SESSIONS = "/api/v1/spaces/{spaceId}/practice-sessions";
    private static final String WRONG = "/api/v1/spaces/{spaceId}/wrong-questions";
    private static final String REVIEW_TASKS = "/api/v1/spaces/{spaceId}/review-tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpikeJwtTokenService spikeJwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

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

    /** One-question practice run answered with the given key and finished. */
    private Long runPractice(String token, Long spaceId, Long questionId, String answerKey)
            throws Exception {
        String body = mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + questionId + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/start", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String detail = mockMvc.perform(get(SESSIONS + "/{sessionId}", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String marker = "\"practiceSessionQuestionId\":";
        int start = detail.indexOf(marker);
        int valueStart = start + marker.length();
        int end = detail.indexOf(',', valueStart);
        if (end < 0) {
            end = detail.indexOf('}', valueStart);
        }
        Long slotId = Long.parseLong(detail.substring(valueStart, end).trim());
        mockMvc.perform(post(SESSIONS + "/{sessionId}/answers", spaceId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"practiceSessionQuestionId\":" + slotId + ","
                                + "\"answer\":{\"selectedOptionKeys\":[\"" + answerKey + "\"]}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return sessionId;
    }

    private Long pendingReviewTaskId(String token, Long spaceId, Long questionId) throws Exception {
        return reviewTaskIds(token, spaceId, questionId, "PENDING").get(0);
    }

    /**
     * Ids of the review tasks returned by GET /review-tasks for one question,
     * restricted to the given status. The API is expected to return open tasks
     * only, so this filter only ever sees the empty set for a non-PENDING
     * status; it is never used to hide a completed task from an assertion.
     *
     * <p>Parsed with Jackson rather than string matching: the response field
     * order (id, targetType, targetId, reason, dueAt, priority, status,
     * createdAt) is not a contract to depend on, and a hand-rolled
     * indexOf-based slice lost the leading {@code id} of every item.
     */
    private List<Long> reviewTaskIds(String token, Long spaceId, Long questionId, String status)
            throws Exception {
        String list = mockMvc.perform(get(REVIEW_TASKS, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode array = objectMapper.readTree(list);
        assertTrue(array.isArray(), "review-task list must be a JSON array: " + list);
        List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode item : array) {
            if ("QUESTION".equals(item.path("targetType").asText())
                    && item.path("targetId").asLong(-1L) == questionId
                    && status.equals(item.path("status").asText())) {
                ids.add(item.path("id").asLong());
            }
        }
        return ids;
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

    private int wrongCountOf(Long spaceId, Long questionId) {
        return jdbcTemplate.queryForObject(
                "SELECT wrong_count FROM wrong_question WHERE space_id = ? AND question_id = ?",
                Integer.class, spaceId, questionId);
    }

    private String wrongStatusOf(Long spaceId, Long questionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM wrong_question WHERE space_id = ? AND question_id = ?",
                String.class, spaceId, questionId);
    }

    // ==================== practice → wrong/review ====================

    /** (1) wrong answer → wrong_question row + review task due +1d. */
    @Test
    void wrongAnswerCreatesWrongQuestionAndReviewTask() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");

        runPractice(token, spaceId, q1, "B"); // wrong

        assertEquals(1, wrongCountOf(spaceId, q1));
        assertEquals("ACTIVE", wrongStatusOf(spaceId, q1));

        mockMvc.perform(get(WRONG, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].questionId").value(q1))
                .andExpect(jsonPath("$[0].wrongCount").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        // review task due ~ now+1d
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                "SELECT * FROM review_task WHERE space_id = ? AND target_id = ?",
                spaceId, q1);
        assertEquals(1, tasks.size());
        LocalDateTime due = LocalDateTime.parse(String.valueOf(tasks.get(0).get("due_at"))
                .replace(' ', 'T'));
        LocalDateTime now = LocalDateTime.now();
        assertTrue(due.isAfter(now.plusHours(20)) && due.isBefore(now.plusHours(28)),
                "due must be ~+1d: " + due);
    }

    /** (2) repeated wrong → wrong_count++ and last_wrong_at refresh. */
    @Test
    void repeatedWrongIncrementsCount() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");

        runPractice(token, spaceId, q1, "B");
        runPractice(token, spaceId, q1, "B");

        assertEquals(2, wrongCountOf(spaceId, q1));
        mockMvc.perform(get(WRONG, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].wrongCount").value(2));
        // only ONE review task (rescheduled, not duplicated)
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_task WHERE space_id = ? AND target_id = ?",
                Integer.class, spaceId, q1);
        assertEquals(1, tasks, "repeated wrong must not duplicate review tasks");
    }

    /** (3) correct answer does NOT create wrong_question. */
    @Test
    void correctAnswerCreatesNoWrongRow() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");

        runPractice(token, spaceId, q1, "A"); // correct

        Integer wrong = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wrong_question WHERE space_id = ? AND question_id = ?",
                Integer.class, spaceId, q1);
        assertEquals(0, wrong);
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_task WHERE space_id = ? AND target_id = ?",
                Integer.class, spaceId, q1);
        assertEquals(0, tasks);
    }

    // ==================== review completion ====================

    /** (4) review WRONG → wrong_count++, ACTIVE, next task +1d. */
    @Test
    void reviewWrongResetsToActive() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        runPractice(token, spaceId, q1, "B");
        Long taskId = pendingReviewTaskId(token, spaceId, q1);

        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", spaceId, taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"WRONG\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.wrongQuestionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.nextDueAt").exists());

        assertEquals(2, wrongCountOf(spaceId, q1));
        // a NEW pending task exists (due +1d); the completed one is no longer listed
        String listAfterComplete = mockMvc.perform(get(REVIEW_TASKS, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> pendingIds = reviewTaskIds(token, spaceId, q1, "PENDING");
        assertEquals(1, pendingIds.size(), "exactly one open task after completion: " + listAfterComplete);
        Long nextTask = pendingIds.get(0);
        assertTrue(!listAfterComplete.contains("\"id\":" + taskId + ","),
                "completed task must not be listed again: " + listAfterComplete);
        assertTrue(nextTask > taskId, "new task id must differ: " + nextTask + " vs " + taskId);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_record WHERE review_task_id = ?",
                Integer.class, taskId);
        assertEquals(1, records, "review record must be immutable history");
    }

    /** (5) review CORRECT → IMPROVING, next task +3d; consecutive CORRECT → MASTERED. */
    @Test
    void reviewCorrectImprovesThenMasters() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        runPractice(token, spaceId, q1, "B");

        // first correct review → IMPROVING + task +3d
        Long task1 = pendingReviewTaskId(token, spaceId, q1);
        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", spaceId, task1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"CORRECT\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wrongQuestionStatus").value("IMPROVING"))
                .andExpect(jsonPath("$.nextDueAt").exists());
        assertEquals("IMPROVING", wrongStatusOf(spaceId, q1));

        // complete the next one correctly too → MASTERED, no new task
        Long task2 = pendingReviewTaskId(token, spaceId, q1);
        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", spaceId, task2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"CORRECT\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wrongQuestionStatus").value("MASTERED"))
                .andExpect(jsonPath("$.nextDueAt").value(org.hamcrest.Matchers.nullValue()));
        assertEquals("MASTERED", wrongStatusOf(spaceId, q1));

        // no more pending tasks for this question — and none at all for either status
        String list = mockMvc.perform(get(REVIEW_TASKS, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(!list.contains("\"targetId\":" + q1), "no pending task after MASTERED: " + list);
        assertTrue(reviewTaskIds(token, spaceId, q1, "PENDING").isEmpty(),
                "no open review task after MASTERED: " + list);
        // completed tasks DO exist in the DB for this question, yet the open-only
        // list omits them — proves the empty list is API filtering, not absence
        Integer completedInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_task WHERE space_id = ? AND target_id = ? "
                        + "AND status = 'COMPLETED'",
                Integer.class, spaceId, q1);
        assertTrue(completedInDb >= 2,
                "completed tasks must be persisted for this question: " + completedInDb);
        assertTrue(reviewTaskIds(token, spaceId, q1, "COMPLETED").isEmpty(),
                "completed tasks are not listed (open-only list): " + list);
    }

    /** (6) broken streak: CORRECT after a WRONG review → IMPROVING not MASTERED. */
    @Test
    void brokenCorrectStreakStaysImproving() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        runPractice(token, spaceId, q1, "B");

        Long t1 = pendingReviewTaskId(token, spaceId, q1);
        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", spaceId, t1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"WRONG\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Long t2 = pendingReviewTaskId(token, spaceId, q1);
        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", spaceId, t2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"CORRECT\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wrongQuestionStatus").value("IMPROVING"));
        assertEquals("IMPROVING", wrongStatusOf(spaceId, q1));
    }

    // ==================== guards ====================

    /** (7) re-complete → 409; invalid result → 400. */
    @Test
    void recompleteAndInvalidResultRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        runPractice(token, spaceId, q1, "B");
        Long taskId = pendingReviewTaskId(token, spaceId, q1);

        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", spaceId, taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"CORRECT\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", spaceId, taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"CORRECT\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", spaceId, taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"MAYBE\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** (8) dueBefore filter + owner isolation + cross-space 404. */
    @Test
    void listGuardsAndDueFilter() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, space1, "q1");
        runPractice(token, space1, q1, "B");

        // dueBefore in the past → empty; in the future → 1
        mockMvc.perform(get(REVIEW_TASKS, space1)
                        .param("dueBefore", "2020-01-01T00:00:00")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(REVIEW_TASKS, space1)
                        .param("dueBefore", "2099-01-01T00:00:00")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // non-owner list → 404; cross-space review completion → 404
        mockMvc.perform(get(WRONG, space1)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        // a legitimate OWNED but EMPTY space returns 200 + empty list (not 404)
        mockMvc.perform(get(REVIEW_TASKS, space2)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
        Long taskId = pendingReviewTaskId(token, space1, q1);
        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", space2, taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"CORRECT\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(REVIEW_TASKS + "/{taskId}/complete", space1, taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"CORRECT\"}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        // anonymous → 401
        mockMvc.perform(get(WRONG, space1))
                .andExpect(status().isUnauthorized());
    }

    /** (9) transaction atomicity: a failed finish must not leave wrong rows. */
    @Test
    void failedFinishLeavesNoWrongRows() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long q1 = createPublishedQuestion(token, spaceId, "q1");
        // answer wrong but NEVER start the session → finish 409, no rows
        String body = mockMvc.perform(post(SESSIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionIds\":[" + q1 + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long sessionId = extractJsonLong(body, "id");
        mockMvc.perform(post(SESSIONS + "/{sessionId}/finish", spaceId, sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        Integer wrong = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wrong_question WHERE space_id = ? AND question_id = ?",
                Integer.class, spaceId, q1);
        assertEquals(0, wrong);
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
                    "Refusing to run WrongQuestionReviewIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
