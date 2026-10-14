package com.aistudy.server.exam;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The exam answer path for ORDERING, plus what the result view says about
 * grading state.
 *
 * <h3>The defect this pins (reported against a live attempt)</h3>
 *
 * <p>{@code ExamAttemptService.answer} built the stored payload with the
 * three-field {@code buildAnswerPayloadJson} overload, which has no
 * {@code orderingAnswer}/{@code matchingAnswer} slots. An ORDERING answer was
 * therefore accepted with 200 while the response and every later read showed an
 * empty payload, and submit then died on the evaluator's
 * "ORDERING requires orderingAnswer" — which the generic handler rendered as
 * {@code 400 VALIDATION_ERROR / 请求包含无法处理的参数}. The learner had an answer the
 * server claimed to hold, a paper it could not hand in, and no way to find out
 * which question was at fault.
 *
 * <p>Two rules are guaranteed here:
 * <ul>
 *   <li>what was posted is what is stored, read back, and graded;</li>
 *   <li>if an answer cannot be graded against its snapshot, the response names
 *       the question — for rows the old build already wrote, that code is the
 *       only thing telling the client what to re-answer.</li>
 * </ul>
 *
 * <p>The result view also carries {@code gradingStatus} and
 * {@code pendingGradeCount}: a partially graded paper previously showed only a
 * number, which the result page read as final.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExamOrderingAnswerIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String LEARNER = "biz-ordering-learner";
    private static final String GRADER = "biz-ordering-grader";
    private static final String ADMIN_EXAMS = "/api/v1/admin/exams";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    private final ObjectMapper json = new ObjectMapper();

    private String learnerToken;
    private Long spaceId;

    @BeforeAll
    void guardTargetDatabase() {
        assertSchema();
    }

    @BeforeEach
    void prepare() {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        OwnedSpaceReset.forSubjects(jdbc, LEARNER, GRADER);
        spaceId = insertSpace(LEARNER, "排序题空间");
        learnerToken = jwtAccessTokenService.issueAccessToken(LEARNER, List.of("USER"));
    }

    @AfterEach
    void clean() {
        OwnedSpaceReset.forSubjects(jdbc, LEARNER, GRADER);
    }

    /** The whole reported chain, end to end: post → read back → submit → graded. */
    @Test
    void orderingAnswerIsStoredReadAndGraded() throws Exception {
        Long questionId = publishQuestion(
                "{\"questionType\":\"ORDERING\",\"stem\":\"按发生顺序排列\","
                        + "\"options\":[{\"optionKey\":\"A\",\"content\":\"甲\"},"
                        + "{\"optionKey\":\"B\",\"content\":\"乙\"},"
                        + "{\"optionKey\":\"C\",\"content\":\"丙\"}],"
                        + "\"correctOrder\":[2,3,1]}");
        Fixture fixture = openAttempt(questionId, 3);

        JsonNode stored = object(postJson(learnerToken,
                answersUrl(fixture.attemptId()),
                "{\"examQuestionId\":" + fixture.slotId()
                        + ",\"answer\":{\"orderingAnswer\":[2,3,1]}}"));
        assertEquals("GRADED", stored.get("gradingStatus").asText(),
                "an objective slot is scored at answer time");
        assertEquals(3, stored.get("answer").get("orderingAnswer").size(),
                "the stored echo must carry the permutation it was given: " + stored);
        assertEquals(2, stored.get("answer").get("orderingAnswer").get(0).asInt());

        JsonNode question = object(readAs(attemptUrl(fixture.attemptId()), learnerToken))
                .get("questions").get(0);
        assertTrue(question.get("answered").asBoolean(), "the slot must read as answered");
        JsonNode echo = question.get("answer");
        assertTrue(echo != null && echo.has("orderingAnswer"),
                "a reloaded attempt must rebuild which slots were filled: " + question);
        assertEquals(3, echo.get("orderingAnswer").size());

        JsonNode result = object(postJson(learnerToken,
                "/api/v1/spaces/" + spaceId + "/exam-attempts/" + fixture.attemptId() + "/submit",
                null));
        assertEquals("GRADED", result.get("gradingStatus").asText());
        assertEquals(0, result.get("pendingGradeCount").asInt());
        assertEquals(2, result.get("score").asInt(), "the right permutation earns the slot");
        assertTrue(result.get("items").get(0).get("isCorrect").asBoolean());
    }

    /** A wrong permutation is scored wrong, not refused. */
    @Test
    void wrongPermutationScoresZero() throws Exception {
        Long questionId = publishQuestion(
                "{\"questionType\":\"ORDERING\",\"stem\":\"按发生顺序排列\","
                        + "\"options\":[{\"optionKey\":\"A\",\"content\":\"甲\"},"
                        + "{\"optionKey\":\"B\",\"content\":\"乙\"},"
                        + "{\"optionKey\":\"C\",\"content\":\"丙\"}],"
                        + "\"correctOrder\":[2,3,1]}");
        Fixture fixture = openAttempt(questionId, 3);
        postJson(learnerToken, answersUrl(fixture.attemptId()),
                "{\"examQuestionId\":" + fixture.slotId()
                        + ",\"answer\":{\"orderingAnswer\":[1,2,3]}}");

        JsonNode result = object(postJson(learnerToken,
                "/api/v1/spaces/" + spaceId + "/exam-attempts/" + fixture.attemptId() + "/submit",
                null));
        assertEquals(0, result.get("score").asInt());
        assertFalse(result.get("items").get(0).get("isCorrect").asBoolean());
    }

    /**
     * Rows the previous build already wrote are still ungradeable, so the answer
     * has to name the question. A generic 400 left the client with "校验未通过" and
     * a learner who could not tell which of ten questions to re-answer.
     */
    @Test
    void anUngradeableStoredAnswerNamesTheQuestion() throws Exception {
        Long questionId = publishQuestion(
                "{\"questionType\":\"ORDERING\",\"stem\":\"按发生顺序排列\","
                        + "\"options\":[{\"optionKey\":\"A\",\"content\":\"甲\"},"
                        + "{\"optionKey\":\"B\",\"content\":\"乙\"},"
                        + "{\"optionKey\":\"C\",\"content\":\"丙\"}],"
                        + "\"correctOrder\":[2,3,1]}");
        Fixture fixture = openAttempt(questionId, 3);
        postJson(learnerToken, answersUrl(fixture.attemptId()),
                "{\"examQuestionId\":" + fixture.slotId()
                        + ",\"answer\":{\"orderingAnswer\":[2,3,1]}}");
        // Simulate the old write: the payload row survives without its ordering.
        jdbc.update("UPDATE exam_answer SET answer_data_json = '{\"textAnswer\":\"甲乙丙\"}'"
                + " WHERE exam_attempt_id = ?", fixture.attemptId());

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/exam-attempts/"
                        + fixture.attemptId() + "/submit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + learnerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXAM_ANSWER_PAYLOAD_INVALID"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("type=ORDERING")))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString(
                                "examQuestionId=" + fixture.slotId())));

        // The attempt stays open so the answer can be re-posted and handed in.
        mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(attemptUrl(fixture.attemptId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + learnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    /**
     * A mixed paper must say its score is provisional, and stop saying so once
     * the human grade lands — the result page has nothing else to read.
     */
    @Test
    void resultCarriesTheGradingVerdictAndPendingCount() throws Exception {
        Long ordering = publishQuestion(
                "{\"questionType\":\"ORDERING\",\"stem\":\"按发生顺序排列\","
                        + "\"options\":[{\"optionKey\":\"A\",\"content\":\"甲\"},"
                        + "{\"optionKey\":\"B\",\"content\":\"乙\"}],"
                        + "\"correctOrder\":[1,2]}");
        Long essay = publishQuestion(
                "{\"questionType\":\"SHORT_ANSWER\",\"stem\":\"简述索引\","
                        + "\"referenceAnswer\":\"B+ 树叶子链\","
                        + "\"explanation\":\"答出叶子双向链即可给分\"}");

        Long examId = createAndPublishExam(List.of(ordering, essay));
        Long attemptId = idOf(postJson(learnerToken,
                "/api/v1/spaces/" + spaceId + "/exams/" + examId + "/sessions", null));
        postJson(learnerToken, "/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId
                + "/start", null);
        postJson(learnerToken, answersUrl(attemptId),
                "{\"examQuestionId\":" + slotOf(examId, ordering)
                        + ",\"answer\":{\"orderingAnswer\":[1,2]}}");
        Long essayAnswerId = idOf(postJson(learnerToken, answersUrl(attemptId),
                "{\"examQuestionId\":" + slotOf(examId, essay)
                        + ",\"answer\":{\"textAnswer\":\"叶子节点双向链\"}}"));

        JsonNode result = object(postJson(learnerToken,
                "/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId + "/submit", null));
        assertEquals("PARTIALLY_GRADED", result.get("gradingStatus").asText());
        assertEquals(1, result.get("pendingGradeCount").asInt());
        assertEquals(2, result.get("score").asInt(), "only the objective part is scored");
        assertTrue(result.get("items").get(1).get("isCorrect").isNull(),
                "the ungraded subjective row is not claimed correct");

        mockMvc.perform(put(ADMIN_EXAMS + "/" + examId + "/attempts/" + attemptId
                        + "/answers/" + essayAnswerId + "/grade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":2}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtAccessTokenService
                                .issueAccessToken(GRADER, List.of("USER", "ADMIN"))))
                .andExpect(status().isOk())
                // Q2: the reviewer is graded against a rubric, not a guess.
                .andExpect(jsonPath("$.referenceAnswer").value("B+ 树叶子链"))
                .andExpect(jsonPath("$.explanation").value("答出叶子双向链即可给分"));

        JsonNode after = object(readAs(resultUrl(attemptId), learnerToken));
        assertEquals("GRADED", after.get("gradingStatus").asText());
        assertEquals(0, after.get("pendingGradeCount").asInt());
        assertEquals(4, after.get("score").asInt());
    }

    // ==================== helpers ====================

    private record Fixture(Long examId, Long attemptId, Long slotId) {
    }

    private Fixture openAttempt(Long questionId, int slotScore) throws Exception {
        Long examId = createAndPublishExam(List.of(questionId));
        Long attemptId = idOf(postJson(learnerToken,
                "/api/v1/spaces/" + spaceId + "/exams/" + examId + "/sessions", null));
        postJson(learnerToken, "/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId
                + "/start", null);
        return new Fixture(examId, attemptId, slotOf(examId, questionId));
    }

    private String answersUrl(Long attemptId) {
        return "/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId + "/answers";
    }

    private String attemptUrl(Long attemptId) {
        return "/api/v1/spaces/" + spaceId + "/exam-attempts/" + attemptId;
    }

    private String resultUrl(Long attemptId) {
        return attemptUrl(attemptId) + "/result";
    }

    private Long createAndPublishExam(List<Long> questionIds) throws Exception {
        StringBuilder questions = new StringBuilder();
        for (Long questionId : questionIds) {
            if (questions.length() > 0) {
                questions.append(',');
            }
            questions.append("{\"questionId\":").append(questionId)
                    .append(",\"score\":2}");
        }
        Long examId = idOf(postJson(learnerToken, "/api/v1/spaces/" + spaceId + "/exams",
                "{\"title\":\"排序卷\",\"timeLimitMinutes\":60,\"questions\":[" + questions + "]}"));
        postJson(learnerToken, "/api/v1/spaces/" + spaceId + "/exams/" + examId + "/publish", null);
        return examId;
    }

    private Long publishQuestion(String definition) throws Exception {
        Long id = idOf(postJson(learnerToken, "/api/v1/spaces/" + spaceId + "/questions",
                definition));
        postJson(learnerToken, "/api/v1/spaces/" + spaceId + "/questions/" + id + "/publish", null);
        return id;
    }

    private Long slotOf(Long examId, Long questionId) {
        return jdbc.queryForObject(
                "SELECT eq.id FROM exam_question eq JOIN exam_paper ep ON ep.id = eq.exam_paper_id"
                        + " WHERE ep.exam_id = ? AND eq.question_id = ? LIMIT 1",
                Long.class, examId, questionId);
    }

    private MvcResult postJson(String token, String url, String content) throws Exception {
        var builder = post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (content != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return mockMvc.perform(builder)
                .andExpect(result -> assertTrue(
                        result.getResponse().getStatus() == 200
                                || result.getResponse().getStatus() == 201,
                        "unexpected " + result.getResponse().getStatus() + " for " + url + ": "
                                + result.getResponse().getContentAsString()))
                .andReturn();
    }

    /** A GET as one caller; named readAs so it does not hide MockMvc's own get(). */
    private MvcResult readAs(String url, String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JsonNode object(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Long idOf(MvcResult result) throws Exception {
        JsonNode node = object(result);
        assertTrue(node.hasNonNull("id"), "no id in " + node);
        return node.get("id").asLong();
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
