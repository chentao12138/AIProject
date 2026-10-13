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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit F-24 — the blueprint distributions must decide the paper that is
 * actually written.
 *
 * <p>Real MySQL ({@code aistudy_flyway_test}), MockMvc + real JWT. The selection
 * algorithm itself is covered without a database by
 * {@code ExamBlueprintQuotaSelectionTest}; this suite pins the two things only
 * an end-to-end run can show: the persisted {@code exam_question} rows honour
 * both distributions and the declared scores, and a refused blueprint leaves no
 * half-generated exam behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExamBlueprintIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> SUBJECTS = List.of("bp-it-user-1");

    private static final String QUESTIONS = "/api/v1/spaces/{spaceId}/questions";
    private static final String BLUEPRINTS = "/api/v1/spaces/{spaceId}/exam-blueprints";

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
        OwnedSpaceReset.forSubjects(jdbcTemplate, SUBJECTS);
    }

    @AfterEach
    void cleanUp() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, SUBJECTS);
    }

    // ==================== tests ====================

    /**
     * The paper used to be subList(0, total) of the id-ordered pool, so this
     * blueprint produced five SINGLE_CHOICE/EASY questions and still reported
     * success. Both marginals have to hold in the persisted slots.
     */
    @Test
    void persistedPaperHonoursBothDistributions() throws Exception {
        String token = token();
        Long spaceId = insertSpace();
        for (int i = 0; i < 6; i++) {
            publishQuestion(token, spaceId, "SINGLE_CHOICE", "EASY", "easy-single-" + i);
        }
        for (int i = 0; i < 6; i++) {
            publishQuestion(token, spaceId, "SINGLE_CHOICE", "HARD", "hard-single-" + i);
        }
        for (int i = 0; i < 6; i++) {
            publishQuestion(token, spaceId, "MULTIPLE_CHOICE", "EASY", "easy-multiple-" + i);
        }

        Long blueprintId = createBlueprint(token, spaceId, "分布蓝图",
                "{\"questionTypeDistribution\":{\"SINGLE_CHOICE\":3,\"MULTIPLE_CHOICE\":2},"
                        + "\"difficultyDistribution\":{\"EASY\":4,\"HARD\":1},"
                        + "\"totalQuestionCount\":5,\"timeLimitMinutes\":30}");

        MvcResult generated = mockMvc.perform(post(BLUEPRINTS + "/{blueprintId}/generate",
                        spaceId, blueprintId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuestions").value(5))
                .andReturn();

        Long paperId = extractJsonLong(generated.getResponse().getContentAsString(), "examPaperId");
        Map<String, Long> byType = countBy(paperId, "q.question_type");
        Map<String, Long> byDifficulty = countBy(paperId, "q.difficulty");
        assertEquals(Map.of("SINGLE_CHOICE", 3L, "MULTIPLE_CHOICE", 2L), byType);
        assertEquals(Map.of("EASY", 4L, "HARD", 1L), byDifficulty);

        mockMvc.perform(get(BLUEPRINTS + "/{blueprintId}", spaceId, blueprintId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USED"));
    }

    /**
     * A blueprint the pool cannot satisfy is refused with a stable error code,
     * and the refusal must not leave the exam/paper rows the transaction had
     * already inserted (the old behaviour wrote a shorter paper with 200).
     */
    @Test
    void unfillableBlueprintIsRefusedWithoutPartialWrites() throws Exception {
        String token = token();
        Long spaceId = insertSpace();
        for (int i = 0; i < 4; i++) {
            publishQuestion(token, spaceId, "SINGLE_CHOICE", "EASY", "only-single-" + i);
        }

        Long blueprintId = createBlueprint(token, spaceId, "超量蓝图",
                "{\"questionTypeDistribution\":{\"SINGLE_CHOICE\":3,\"MULTIPLE_CHOICE\":2},"
                        + "\"difficultyDistribution\":{\"EASY\":3,\"HARD\":2},"
                        + "\"totalQuestionCount\":5}");

        mockMvc.perform(post(BLUEPRINTS + "/{blueprintId}/generate", spaceId, blueprintId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_BLUEPRINT_UNSATISFIED"));

        assertEquals(0, examsInSpace(spaceId), "refused generation must not persist an exam");
        assertEquals(0, paperSlots(spaceId), "refused generation must not persist paper slots");
        mockMvc.perform(get(BLUEPRINTS + "/{blueprintId}", spaceId, blueprintId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    /** Quotas that contradict the requested length are an authoring error. */
    @Test
    void contradictoryQuotaIsRejectedAtCreate() throws Exception {
        String token = token();
        Long spaceId = insertSpace();

        mockMvc.perform(post(BLUEPRINTS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"自相矛盾\",\"rules\":"
                                + "{\"questionTypeDistribution\":{\"SINGLE_CHOICE\":7},"
                                + "\"totalQuestionCount\":10}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("must add up to totalQuestionCount")));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_blueprint WHERE space_id = ?",
                Integer.class, spaceId).longValue());
    }

    /**
     * F-24 also covers scoring: scoreByDifficulty used to be ignored in favour
     * of hardcoded 2/5/10, so the paper total never matched the blueprint.
     */
    @Test
    void declaredScoresAreAppliedPerDifficulty() throws Exception {
        String token = token();
        Long spaceId = insertSpace();
        publishQuestion(token, spaceId, "SINGLE_CHOICE", "EASY", "score-easy-1");
        publishQuestion(token, spaceId, "SINGLE_CHOICE", "HARD", "score-hard-1");

        Long blueprintId = createBlueprint(token, spaceId, "分值蓝图",
                "{\"questionTypeDistribution\":{\"SINGLE_CHOICE\":2},"
                        + "\"difficultyDistribution\":{\"EASY\":1,\"HARD\":1},"
                        + "\"scoreByDifficulty\":{\"EASY\":1,\"HARD\":8},"
                        + "\"totalQuestionCount\":2}");

        MvcResult generated = mockMvc.perform(post(BLUEPRINTS + "/{blueprintId}/generate",
                        spaceId, blueprintId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Long paperId = extractJsonLong(generated.getResponse().getContentAsString(), "examPaperId");
        Long examId = extractJsonLong(generated.getResponse().getContentAsString(), "examId");

        Map<String, Long> scoreByDifficulty = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT q.difficulty AS d, eq.score AS s FROM exam_question eq "
                        + "JOIN question q ON q.id = eq.question_id WHERE eq.exam_paper_id = ?",
                paperId)) {
            scoreByDifficulty.put(String.valueOf(row.get("d")), ((Number) row.get("s")).longValue());
        }
        assertEquals(Map.of("EASY", 1L, "HARD", 8L), scoreByDifficulty);
        assertEquals(9, jdbcTemplate.queryForObject(
                "SELECT total_score FROM exam WHERE id = ?", Integer.class, examId).intValue());
    }

    /**
     * A distribution without totalQuestionCount defines the paper length; it
     * must not be read as "use the whole pool", which would make the quotas
     * unsatisfiable by construction.
     */
    @Test
    void distributionWithoutTotalSizesThePaper() throws Exception {
        String token = token();
        Long spaceId = insertSpace();
        publishQuestion(token, spaceId, "SINGLE_CHOICE", "EASY", "no-total-easy-1");
        publishQuestion(token, spaceId, "SINGLE_CHOICE", "EASY", "no-total-easy-2");
        publishQuestion(token, spaceId, "SINGLE_CHOICE", "HARD", "no-total-hard-1");
        publishQuestion(token, spaceId, "SINGLE_CHOICE", "HARD", "no-total-extra-2");

        Long blueprintId = createBlueprint(token, spaceId, "无总数蓝图",
                "{\"difficultyDistribution\":{\"EASY\":2,\"HARD\":1}}");

        mockMvc.perform(post(BLUEPRINTS + "/{blueprintId}/generate", spaceId, blueprintId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuestions").value(3));
    }

    /** Draft rules are validated too, so scoreByDifficulty typos fail early. */
    @Test
    void unknownScoreDifficultyIsRejectedAtCreate() throws Exception {
        String token = token();
        Long spaceId = insertSpace();

        mockMvc.perform(post(BLUEPRINTS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"错误分值\",\"rules\":"
                                + "{\"scoreByDifficulty\":{\"TRIVIAL\":5},"
                                + "\"totalQuestionCount\":1}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("scoreByDifficulty")));
    }

    // ==================== helpers ====================

    private String token() {
        String token = jwtAccessTokenService.issueAccessToken("bp-it-user-1");
        assertNotNull(token, "token must not be null");
        return token;
    }

    private Long insertSpace() {
        String owner = "bp-it-user-1";
        jdbcTemplate.update(
                "INSERT INTO learning_space "
                        + "(name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                "蓝图测试空间", owner);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject = ? AND name = ? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, owner, "蓝图测试空间");
    }

    private Long publishQuestion(String token, Long spaceId, String type, String difficulty,
                                 String stem) throws Exception {
        String answerPart = "SINGLE_CHOICE".equals(type)
                ? "\"correctOptionKey\":\"A\""
                : "\"correctOptionKeys\":[\"A\",\"B\"]";
        MvcResult result = mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"" + type + "\",\"stem\":\"" + stem + "\","
                                + "\"difficulty\":\"" + difficulty + "\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"},"
                                + "{\"optionKey\":\"C\",\"content\":\"c\"}],"
                                + answerPart + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long id = extractJsonLong(result.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return id;
    }

    private Long createBlueprint(String token, Long spaceId, String title,
                                 String rulesJson) throws Exception {
        MvcResult result = mockMvc.perform(post(BLUEPRINTS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"rules\":" + rulesJson + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return extractJsonLong(result.getResponse().getContentAsString(), "id");
    }

    private Map<String, Long> countBy(Long paperId, String expression) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT " + expression + " AS k, COUNT(*) AS c FROM exam_question eq "
                        + "JOIN question q ON q.id = eq.question_id "
                        + "WHERE eq.exam_paper_id = ? GROUP BY " + expression,
                paperId)) {
            counts.put(String.valueOf(row.get("k")), ((Number) row.get("c")).longValue());
        }
        return counts;
    }

    private long examsInSpace(Long spaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam WHERE space_id = ?", Integer.class, spaceId).longValue();
    }

    private long paperSlots(Long spaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_question eq JOIN exam_paper ep ON ep.id = eq.exam_paper_id "
                        + "WHERE ep.space_id = ?", Integer.class, spaceId).longValue();
    }

    private static Long extractJsonLong(String body, String field) {
        String marker = "\"" + field + "\":";
        int start = body.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("body must contain field '" + field + "': " + body);
        }
        int valueStart = start + marker.length();
        int end = body.indexOf(',', valueStart);
        if (end < 0) {
            end = body.indexOf('}', valueStart);
        }
        return Long.parseLong(body.substring(valueStart, end).trim());
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run ExamBlueprintIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
