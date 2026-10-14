package com.aistudy.server.exam;

import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.testsupport.OwnedSpaceReset;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E-04 / E-07 — the ADMIN grading chain against real MySQL.
 *
 * <h3>What broke before</h3>
 * <ul>
 *   <li>Manual grading resolved the attempt with the <em>caller</em> as the
 *       examinee, so the only role allowed to grade could never reach another
 *       learner's attempt — every request answered 404.</li>
 *   <li>{@code POST /admin/exams/{examId}/attempts/{attemptId}/grade} flipped
 *       {@code grading_status} to GRADED without writing any score and skipped
 *       the result/diagnosis/mastery recompute, so an ungraded paper looked
 *       final.</li>
 *   <li>A grade wrote {@code score} but not {@code is_correct}, so a full-marks
 *       essay still counted as wrong in the result — and in the diagnosis that
 *       same recompute writes.</li>
 * </ul>
 *
 * <h3>What is guaranteed now</h3>
 * <ul>
 *   <li>ADMIN grading goes through the domain service: the score is stored,
 *       is_correct follows it, and result + diagnosis + mastery are recomputed
 *       from the answer rows.</li>
 *   <li>Finalizing refuses with 409 {@code EXAM_GRADING_INCOMPLETE} while a
 *       stored subjective answer still awaits a score.</li>
 *   <li>Objective slots refuse a hand-written score
 *       ({@code EXAM_ANSWER_NOT_SUBJECTIVE}); they are scored from the snapshot.</li>
 *   <li>Regrading corrects the score and keeps the first one in
 *       {@code previous_score}, attributed to the human who graded.</li>
 *   <li>The AI suggestion is advice only: it never writes a score, clamps an
 *       impossible number, and is booked in the usage ledger.</li>
 * </ul>
 *
 * <p>The learner owns the space and the exam; the grader is a different subject
 * with ROLE_ADMIN and no space of its own — exactly the case the old, owner-bound
 * lookup excluded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExamAdminGradingIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String LEARNER = "biz-admin-grade-learner";
    private static final String GRADER = "biz-admin-grade-grader";

    private static final String ADMIN_EXAMS = "/api/v1/admin/exams";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @MockitoBean
    private AiProvider aiProvider;

    private final ObjectMapper json = new ObjectMapper();

    private Long spaceId;
    private Long essayQuestionId;
    private Long choiceQuestionId;
    private Long examId;
    private Long attemptId;

    @BeforeAll
    void guardTargetDatabase() {
        assertSchema();
    }

    @DynamicPropertySource
    static void aiProps(DynamicPropertyRegistry registry) {
        // The suggestion endpoint needs a configured provider; the mock replaces it.
        registry.add("aistudy.ai.enabled", () -> "true");
        registry.add("aistudy.ai.base-url", () -> "http://127.0.0.1:9");
        registry.add("aistudy.ai.api-key", () -> "test-api-key");
        registry.add("aistudy.ai.model", () -> "test-model");
    }

    @BeforeEach
    void prepare() throws Exception {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        OwnedSpaceReset.forSubjects(jdbc, LEARNER, GRADER);
        spaceId = insertSpace(LEARNER, "改分空间");
        String learnerToken = learnerToken();

        essayQuestionId = publishQuestion(learnerToken,
                "{\"questionType\":\"SHORT_ANSWER\",\"stem\":\"简述 B+ 树的查询\","
                        + "\"referenceAnswer\":\"从根向下遍历\"}");
        choiceQuestionId = publishQuestion(learnerToken,
                "{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"选择一\","
                        + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                        + "{\"optionKey\":\"B\",\"content\":\"b\"}],"
                        + "\"correctOptionKey\":\"A\"}");

        examId = createAndPublishExam(learnerToken, essayQuestionId, choiceQuestionId);

        attemptId = idOf(body(postAs(learnerToken,
                "/api/v1/spaces/" + spaceId + "/exams/" + examId + "/sessions", null)));
        postAs(learnerToken, "/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId + "/start",
                null);
        answer(learnerToken, slotOf(essayQuestionId),
                "{\"textAnswer\":\"从根向下遍历，再回写父节点\"}");
        answer(learnerToken, slotOf(choiceQuestionId), "{\"selectedOptionKeys\":[\"A\"]}");
        postAs(learnerToken, "/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId + "/submit",
                null);
    }

    @AfterEach
    void clean() {
        OwnedSpaceReset.forSubjects(jdbc, LEARNER, GRADER);
    }

    /**
     * Reading the queue is the first half of grading: the old admin surface
     * exposed no attempt contents at all, so a grader had neither the learner's
     * text nor the slot's bounds to judge against.
     */
    @Test
    void graderSeesEveryAnswerWithItsSlotBoundsAndText() throws Exception {
        JsonNode queue = array(admin(get(queueUrl())));
        assertEquals(2, queue.size(), "one subjective plus one objective answer");

        JsonNode essay = byType(queue, "SHORT_ANSWER");
        assertEquals("UNGRADED", essay.get("gradingStatus").asText());
        assertEquals(4, essay.get("maxScore").asInt());
        assertEquals("从根向下遍历，再回写父节点", essay.get("answerText").asText());
        assertTrue(essay.get("stem").asText().contains("B+ 树"), essay.get("stem").asText());
        assertTrue(essay.get("score").isNull(), "nothing is scored before grading");
        assertEquals(2, byType(queue, "SINGLE_CHOICE").get("maxScore").asInt());
    }

    /**
     * The gap this closes: an ADMIN grading a learner it is not. Under the old
     * owner-bound lookup this answered 404 for every real learner's attempt.
     */
    @Test
    void adminGradesAnotherLearnersAttemptAndTheResultFollows() throws Exception {
        Long answerId = essayAnswerId();

        JsonNode graded = object(mockMvc.perform(put(gradeUrl(answerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":3,\"feedback\":\"要点齐全\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals("GRADED", graded.get("gradingStatus").asText());
        assertEquals(3, graded.get("score").asInt());
        assertFalse(graded.get("isCorrect").asBoolean(), "3 of 4 is not full marks");
        assertEquals(GRADER, graded.get("gradedBy").asText(),
                "the human who decided is attributed, not the learner");

        assertEquals(3, scoreOf(answerId));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_correct FROM exam_answer WHERE id = ?", Boolean.class, answerId)));
        assertEquals("要点齐全", jdbc.queryForObject(
                "SELECT feedback FROM exam_answer WHERE id = ?", String.class, answerId));

        mockMvc.perform(post(finalizeUrl())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gradingStatus").value("GRADED"))
                .andExpect(jsonPath("$.score").value(5))
                .andExpect(jsonPath("$.maxScore").value(6));
    }

    /**
     * Finalize used to assert GRADED over ungraded work. Refusing is the point:
     * the verdict has to come from the answer rows, never from the request.
     */
    @Test
    void finalizeRefusesWhileAnAnswerAwaitsAGrade() throws Exception {
        mockMvc.perform(post(finalizeUrl())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_GRADING_INCOMPLETE"));

        grade(essayAnswerId(), 4);

        mockMvc.perform(post(finalizeUrl())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gradingStatus").value("GRADED"))
                .andExpect(jsonPath("$.pendingGradeCount").value(0));
    }

    /**
     * A full-marks grade must also make the answer correct. Before, is_correct
     * kept its pre-grade value, so a perfect essay was still counted wrong in
     * exam_result and in everything derived from it.
     */
    @Test
    void fullMarksGradeCountsTheAnswerCorrect() throws Exception {
        grade(essayAnswerId(), 4);

        assertEquals(6, jdbc.queryForObject(
                "SELECT score FROM exam_result WHERE exam_attempt_id = ?", Integer.class, attemptId));
        assertEquals(6, jdbc.queryForObject(
                "SELECT max_score FROM exam_result WHERE exam_attempt_id = ?",
                Integer.class, attemptId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT correct_count FROM exam_result WHERE exam_attempt_id = ?",
                Integer.class, attemptId), "essay and choice are both correct");
        assertEquals(0, jdbc.queryForObject(
                "SELECT wrong_count FROM exam_result WHERE exam_attempt_id = ?",
                Integer.class, attemptId));
    }

    /** Correcting a grade is the other half of manual grading, with an audit trail. */
    @Test
    void regradeCorrectsTheScoreAndKeepsTheFirstOneAuditable() throws Exception {
        Long answerId = essayAnswerId();
        grade(answerId, 1);
        grade(answerId, 4);

        assertEquals(4, scoreOf(answerId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT previous_score FROM exam_answer WHERE id = ?", Integer.class, answerId),
                "the first grade stays readable");
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_correct FROM exam_answer WHERE id = ?", Boolean.class, answerId)));
        assertEquals(6, jdbc.queryForObject(
                "SELECT score FROM exam_result WHERE exam_attempt_id = ?", Integer.class, attemptId),
                "the result follows the corrected score");
    }

    /** Objective slots are scored from the snapshot; a hand-written score is refused. */
    @Test
    void objectiveSlotRefusesAHandWrittenScore() throws Exception {
        Long objectiveAnswerId = choiceAnswerId();

        mockMvc.perform(put(gradeUrl(objectiveAnswerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":2}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_ANSWER_NOT_SUBJECTIVE"));

        assertNull(jdbc.queryForObject(
                "SELECT graded_at FROM exam_answer WHERE id = ?", Object.class, objectiveAnswerId),
                "a refused grade must not leave an audit stamp");
    }

    /** A score outside the slot bounds is a client bug, so the bound is in the code. */
    @Test
    void scoreOutsideTheSlotBoundsIsRejected() throws Exception {
        mockMvc.perform(put(gradeUrl(essayAnswerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":9}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXAM_GRADE_SCORE_OUT_OF_RANGE"));

        assertNull(scoreOfOrNull(essayAnswerId()), "a rejected score must not be stored");
    }

    /**
     * E-07: the AI may advise and nothing more. The suggestion carries a score
     * and a rationale, is booked in the ledger, and leaves the answer exactly as
     * it was — the score only exists once a human posts it.
     */
    @Test
    void aiSuggestionIsAdvisoryOnly() throws Exception {
        Long answerId = essayAnswerId();
        when(aiProvider.chat(any(AiChatRequest.class), anyString()))
                .thenReturn(new AiChatResponse("{\"score\":3,\"rationale\":\"缺少合并说明\"}",
                        "stepfun", "test-model", null));

        mockMvc.perform(post(suggestionUrl(answerId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedScore").value(3))
                .andExpect(jsonPath("$.maxScore").value(4))
                .andExpect(jsonPath("$.clamped").value(false))
                .andExpect(jsonPath("$.finalScore").value(false))
                .andExpect(jsonPath("$.rationale").value("缺少合并说明"));

        assertNull(scoreOfOrNull(answerId), "a suggestion must never become a score by itself");
        assertEquals("GRADING_SUGGESTION", jdbc.queryForObject(
                "SELECT purpose FROM ai_usage_record WHERE space_id = ? ORDER BY id DESC LIMIT 1",
                String.class, spaceId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_usage_record WHERE purpose = 'GRADING_SUGGESTION'"
                        + " AND status = 'SUCCESS'", Integer.class),
                "the call is booked like every other AI purpose");
    }

    /** Out-of-range model output is clamped into the rubric and says so. */
    @Test
    void aiSuggestionClampsAnImpossibleScore() throws Exception {
        when(aiProvider.chat(any(AiChatRequest.class), anyString()))
                .thenReturn(new AiChatResponse("{\"score\":99,\"rationale\":\"很好\"}",
                        "stepfun", "test-model", null));

        mockMvc.perform(post(suggestionUrl(essayAnswerId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedScore").value(4))
                .andExpect(jsonPath("$.clamped").value(true));
    }

    /** An unparseable answer is an error, not a zero — a silent zero looks harsh but legal. */
    @Test
    void aiSuggestionRefusesAnUnusableResponse() throws Exception {
        when(aiProvider.chat(any(AiChatRequest.class), anyString()))
                .thenReturn(new AiChatResponse("我觉得还不错", "stepfun", "test-model", null));

        mockMvc.perform(post(suggestionUrl(essayAnswerId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_GRADING_SUGGESTION_NOT_AVAILABLE"));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_usage_record WHERE purpose = 'GRADING_SUGGESTION'"
                        + " AND status = 'FAILED'", Integer.class),
                "the failed attempt is still booked");
    }

    /** There is nothing to suggest for an auto-scored slot. */
    @Test
    void aiSuggestionRefusesAnObjectiveSlot() throws Exception {
        mockMvc.perform(post(suggestionUrl(choiceAnswerId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_GRADING_SUGGESTION_NOT_AVAILABLE"));
    }

    /**
     * The URL claims an exam; the attempt has to belong to it. A second exam in
     * the same space is the closest a wrong id can get, and it must still 404.
     */
    @Test
    void attemptOfAnotherExamIsNotFoundThroughTheAdminPath() throws Exception {
        Long otherExam = createAndPublishExam(learnerToken(), choiceQuestionId, essayQuestionId);

        mockMvc.perform(post(ADMIN_EXAMS + "/" + otherExam + "/attempts/" + attemptId + "/grade")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXAM_ATTEMPT_NOT_FOUND"));

        mockMvc.perform(get(ADMIN_EXAMS + "/" + otherExam + "/attempts/" + attemptId + "/answers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXAM_ATTEMPT_NOT_FOUND"));
    }

    /** A learner may not grade their own paper, whatever the URL says. */
    @Test
    void learnerCannotGradeThroughTheSpacePath() throws Exception {
        mockMvc.perform(put("/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId
                        + "/answers/" + essayAnswerId() + "/grade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":4}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + learnerToken()))
                .andExpect(status().isForbidden());

        assertNull(scoreOfOrNull(essayAnswerId()));
    }

    // ==================== helpers ====================

    private String queueUrl() {
        return ADMIN_EXAMS + "/" + examId + "/attempts/" + attemptId + "/answers";
    }

    private String gradeUrl(Long answerId) {
        return ADMIN_EXAMS + "/" + examId + "/attempts/" + attemptId + "/answers/" + answerId
                + "/grade";
    }

    private String finalizeUrl() {
        return ADMIN_EXAMS + "/" + examId + "/attempts/" + attemptId + "/grade";
    }

    private String suggestionUrl(Long answerId) {
        return ADMIN_EXAMS + "/" + examId + "/attempts/" + attemptId + "/answers/" + answerId
                + "/grading-suggestion";
    }

    private void grade(Long answerId, int score) throws Exception {
        mockMvc.perform(put(gradeUrl(answerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":" + score + "}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isOk());
    }

    private MvcResult admin(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + graderToken()))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JsonNode object(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode array(MvcResult result) throws Exception {
        JsonNode node = object(result);
        assertTrue(node.isArray(), "expected a JSON array, got: " + node);
        return node;
    }

    private JsonNode byType(JsonNode queue, String questionType) {
        for (JsonNode node : queue) {
            if (questionType.equals(node.get("questionType").asText())) {
                return node;
            }
        }
        throw new AssertionError("no " + questionType + " answer in " + queue);
    }

    private Long essayAnswerId() {
        return answerIdOf("SHORT_ANSWER");
    }

    private Long choiceAnswerId() {
        return answerIdOf("SINGLE_CHOICE");
    }

    private Long answerIdOf(String questionType) {
        return jdbc.queryForObject(
                "SELECT ea.id FROM exam_answer ea JOIN exam_question eq ON eq.id = ea.exam_question_id"
                        + " WHERE ea.exam_attempt_id = ? AND JSON_UNQUOTE("
                        + " JSON_EXTRACT(eq.question_snapshot_json, '$.questionType')) = ? LIMIT 1",
                Long.class, attemptId, questionType);
    }

    private int scoreOf(Long answerId) {
        Integer score = scoreOfOrNull(answerId);
        assertTrue(score != null, "score expected for answer " + answerId);
        return score;
    }

    private Integer scoreOfOrNull(Long answerId) {
        return jdbc.queryForObject(
                "SELECT score FROM exam_answer WHERE id = ?", Integer.class, answerId);
    }

    private Long slotOf(Long questionId) {
        return jdbc.queryForObject(
                "SELECT eq.id FROM exam_question eq JOIN exam_paper ep ON ep.id = eq.exam_paper_id"
                        + " WHERE ep.exam_id = ? AND eq.question_id = ? LIMIT 1",
                Long.class, examId, questionId);
    }

    private void answer(String token, Long slotId, String payloadJson) throws Exception {
        body(postAs(token, "/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId + "/answers",
                "{\"examQuestionId\":" + slotId + ",\"answer\":" + payloadJson + "}"));
    }

    private Long createAndPublishExam(String token, Long first, Long second) throws Exception {
        Long id = idOf(body(postAs(token, "/api/v1/spaces/" + spaceId + "/exams",
                "{\"title\":\"期中\",\"timeLimitMinutes\":60,\"questions\":["
                        + "{\"questionId\":" + first + ",\"score\":4},"
                        + "{\"questionId\":" + second + ",\"score\":2}]}")));
        postAs(token, "/api/v1/spaces/" + spaceId + "/exams/" + id + "/publish", null);
        return id;
    }

    private Long publishQuestion(String token, String definition) throws Exception {
        Long id = idOf(body(postAs(token, "/api/v1/spaces/" + spaceId + "/questions", definition)));
        postAs(token, "/api/v1/spaces/" + spaceId + "/questions/" + id + "/publish", null);
        return id;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private Long idOf(String body) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("response is not JSON: " + body, e);
        }
        assertTrue(node.hasNonNull("id"), "no id in: " + body);
        return node.get("id").asLong();
    }

    private MvcResult postAs(String token, String url, String content) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url), token, content);
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, String token, String content)
            throws Exception {
        if (content != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(result -> assertTrue(
                        result.getResponse().getStatus() == 200
                                || result.getResponse().getStatus() == 201,
                        "unexpected status " + result.getResponse().getStatus() + ": "
                                + result.getResponse().getContentAsString()))
                .andReturn();
    }

    private String learnerToken() {
        return jwtAccessTokenService.issueAccessToken(LEARNER, List.of("USER"));
    }

    private String graderToken() {
        return jwtAccessTokenService.issueAccessToken(GRADER, List.of("USER", "ADMIN"));
    }

    private Long insertSpace(String ownerSubject, String name) {
        jdbc.update("INSERT INTO learning_space "
                + "(name, description, owner_subject, status, created_at, updated_at) "
                + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, ownerSubject);
        return jdbc.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject = ? AND name = ?"
                        + " ORDER BY id DESC LIMIT 1", Long.class, ownerSubject, name);
    }

    private void assertSchema() {
        assertEquals(EXPECTED_SCHEMA, jdbc.queryForObject("SELECT DATABASE()", String.class),
                "this test must only ever run against the flyway-it schema");
    }
}
