package com.aistudy.server.question;

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
 * BUSINESS-008 — Question bank vertical slice integration test.
 *
 * <p>Real MySQL ({@code flyway-it}), MockMvc + real JWT, schema guard
 * against {@code aistudy_flyway_test}. Covers: create for every
 * supported type; per-type validation (400); same-space knowledge
 * point invariant (404); owner/space isolation (404); filtered list;
 * detail; publish + idempotent re-publish (no timestamp refresh);
 * soft-deleted question invisible; answer data server-written and
 * exposed only through the authoring view.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuestionVerticalSliceIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1", "biz-e2e-user-2");

    private static final String QUESTIONS = "/api/v1/spaces/{spaceId}/questions";
    private static final String POINTS = "/api/v1/spaces/{spaceId}/knowledge-points";

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

    private MvcResult createQuestion(String token, Long spaceId, String body) throws Exception {
        return mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private String singleChoiceBody() {
        return "{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"1+1=?\","
                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"1\"},"
                + "{\"optionKey\":\"B\",\"content\":\"2\"},{\"optionKey\":\"C\",\"content\":\"3\"}],"
                + "\"correctOptionKey\":\"B\",\"explanation\":\"2 is the answer\"}";
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

    private String publishBody(String token, Long spaceId, Long questionId) throws Exception {
        return mockMvc.perform(post(QUESTIONS + "/{questionId}/publish", spaceId, questionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ==================== create per type ====================

    /** (1) SINGLE_CHOICE create → 201, typed authoring view with answer. */
    @Test
    void createSingleChoice() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");

        String body = createQuestion(token, spaceId, singleChoiceBody())
                .getResponse().getContentAsString();

        assertTrue(body.contains("\"status\":\"DRAFT\""), body);
        assertTrue(body.contains("\"correctOptionKey\":\"B\""), body);
        assertTrue(body.contains("\"isCorrect\":true"), body);
        assertTrue(body.contains("\"optionKey\":\"B\""), body);
        assertTrue(body.contains("\"originType\":\"USER_CURATED\""), body);
        assertTrue(body.contains("\"publishedAt\":null"), body);
    }

    /** (2) MULTIPLE_CHOICE exact-set answer stored. */
    @Test
    void createMultipleChoice() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        String body = createQuestion(token, spaceId,
                "{\"questionType\":\"MULTIPLE_CHOICE\",\"stem\":\"pick A,C\","
                        + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                        + "{\"optionKey\":\"B\",\"content\":\"b\"},{\"optionKey\":\"C\",\"content\":\"c\"}],"
                        + "\"correctOptionKeys\":[\"A\",\"C\"]}")
                .getResponse().getContentAsString();
        assertTrue(body.contains("\"correctOptionKeys\":[\"A\",\"C\"]"), body);
        // only A and C flagged correct
        int correctFlags = body.split("\"isCorrect\":true").length - 1;
        assertEquals(2, correctFlags, body);
    }

    /** (3) TRUE_FALSE create. */
    @Test
    void createTrueFalse() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        String body = createQuestion(token, spaceId,
                "{\"questionType\":\"TRUE_FALSE\",\"stem\":\"MySQL is a DB\","
                        + "\"correctBoolean\":true}")
                .getResponse().getContentAsString();
        assertTrue(body.contains("\"correctBoolean\":true"), body);
    }

    /** (4) SHORT_ANSWER create with reference answer (authoring only). */
    @Test
    void createShortAnswer() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        String body = createQuestion(token, spaceId,
                "{\"questionType\":\"SHORT_ANSWER\",\"stem\":\"What is ACID?\","
                        + "\"referenceAnswer\":\"Atomicity Consistency Isolation Durability\"}")
                .getResponse().getContentAsString();
        assertTrue(body.contains("\"referenceAnswer\":\"Atomicity Consistency Isolation Durability\""), body);
        Long id = extractJsonLong(body, "id");
        String dbAnswer = jdbcTemplate.queryForObject(
                "SELECT answer_data_json FROM question WHERE id = ?", String.class, id);
        assertTrue(dbAnswer.contains("referenceAnswer"), "answer_data_json must be server-written: " + dbAnswer);
    }

    /** (5) question linked to a same-space knowledge point. */
    @Test
    void createWithKnowledgePointLinks() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        String body = createQuestion(token, spaceId,
                "{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"s\","
                        + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                        + "{\"optionKey\":\"B\",\"content\":\"b\"}],"
                        + "\"correctOptionKey\":\"A\",\"knowledgePointIds\":[" + kpId + "]}")
                .getResponse().getContentAsString();
        assertTrue(body.contains("\"knowledgePointIds\":[" + kpId + "]"), body);
        Long questionId = extractJsonLong(body, "id");
        Integer links = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question_knowledge_point WHERE question_id = ?",
                Integer.class, questionId);
        assertEquals(1, links);
    }

    // ==================== validation (400) ====================

    /** (6) SINGLE_CHOICE without correctOptionKey → 400. */
    @Test
    void singleChoiceMissingCorrectKeyIsRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"s\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}]}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isBadRequest());
    }

    /** (7) duplicate option keys → 400. */
    @Test
    void duplicateOptionKeysAreRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"s\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"A\",\"content\":\"a2\"}],"
                                + "\"correctOptionKey\":\"A\"}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isBadRequest());
    }

    /** (8) MULTIPLE_CHOICE with empty correct set → 400. */
    @Test
    void multipleChoiceEmptyCorrectSetIsRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"MULTIPLE_CHOICE\",\"stem\":\"s\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}],"
                                + "\"correctOptionKeys\":[]}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isBadRequest());
    }

    /** (9) TRUE_FALSE with options → 400. */
    @Test
    void trueFalseWithOptionsIsRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"TRUE_FALSE\",\"stem\":\"s\","
                                + "\"correctBoolean\":true,"
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}]}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isBadRequest());
    }

    /** (10) unknown type → 400. */
    @Test
    void unknownQuestionTypeIsRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"ESSAY\",\"stem\":\"s\"}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isBadRequest());
    }

    // ==================== isolation (404) ====================

    /** (11) cross-space knowledge point → whole create rejected. */
    @Test
    void crossSpaceKnowledgePointRejectsCreate() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        Long kpInSpace2 = insertFixturePoint(space2, "异空间点");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post(QUESTIONS, space1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"s\","
                                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                                + "{\"optionKey\":\"B\",\"content\":\"b\"}],"
                                + "\"correctOptionKey\":\"A\",\"knowledgePointIds\":[" + kpInSpace2 + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question WHERE space_id = ?", Integer.class, space1);
        assertEquals(0, count, "failed create must leave zero rows");
    }

    /** (12) non-owner space → 404. */
    @Test
    void nonOwnerSpaceIsRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        mockMvc.perform(post(QUESTIONS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleChoiceBody())
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    // ==================== list / detail ====================

    /** (13) list with filters: status + questionType + knowledgePointId. */
    @Test
    void listFiltersWork() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long kpId = insertFixturePoint(spaceId, "点1");
        String token = tokenFor("biz-e2e-user-1");
        String body = createQuestion(token, spaceId,
                "{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"s1\","
                        + "\"options\":[{\"optionKey\":\"A\",\"content\":\"a\"},"
                        + "{\"optionKey\":\"B\",\"content\":\"b\"}],"
                        + "\"correctOptionKey\":\"A\",\"knowledgePointIds\":[" + kpId + "]}")
                .getResponse().getContentAsString();
        Long q1 = extractJsonLong(body, "id");
        createQuestion(token, spaceId,
                "{\"questionType\":\"TRUE_FALSE\",\"stem\":\"tf\",\"correctBoolean\":false}");

        mockMvc.perform(get(QUESTIONS, spaceId)
                        .param("questionType", "SINGLE_CHOICE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(q1));

        mockMvc.perform(get(QUESTIONS, spaceId)
                        .param("status", "DRAFT")
                        .param("knowledgePointId", String.valueOf(kpId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // other owner cannot list this space
        mockMvc.perform(get(QUESTIONS, spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (14) detail + cross-space 404. */
    @Test
    void detailIsolation() throws Exception {
        Long space1 = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long space2 = insertFixtureSpace("biz-e2e-user-1", "user1 空间2");
        String token = tokenFor("biz-e2e-user-1");
        String body = createQuestion(token, space1, singleChoiceBody())
                .getResponse().getContentAsString();
        Long questionId = extractJsonLong(body, "id");

        mockMvc.perform(get(QUESTIONS + "/{questionId}", space1, questionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(questionId));

        // same question id under another space → 404
        mockMvc.perform(get(QUESTIONS + "/{questionId}", space2, questionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // other owner → 404
        mockMvc.perform(get(QUESTIONS + "/{questionId}", space1, questionId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
        // anonymous → 401
        mockMvc.perform(get(QUESTIONS + "/{questionId}", space1, questionId))
                .andExpect(status().isUnauthorized());
    }

    // ==================== publish ====================

    /** (15) publish DRAFT→PUBLISHED; republish idempotent, timestamps untouched. */
    @Test
    void publishAndRepublishIsIdempotent() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        String body = createQuestion(token, spaceId, singleChoiceBody())
                .getResponse().getContentAsString();
        Long questionId = extractJsonLong(body, "id");

        String published = publishBody(token, spaceId, questionId);
        assertTrue(published.contains("\"status\":\"PUBLISHED\""), published);
        String publishedAt = published.replaceFirst(".*\"publishedAt\":\"([^\"]*)\".*", "$1");
        String updatedAt = published.replaceFirst(".*\"updatedAt\":\"([^\"]*)\".*", "$1");

        String republished = publishBody(token, spaceId, questionId);
        assertTrue(republished.contains("\"status\":\"PUBLISHED\""), republished);
        assertTrue(republished.contains("\"publishedAt\":\"" + publishedAt + "\""),
                "republish must not refresh publishedAt");
        assertTrue(republished.contains("\"updatedAt\":\"" + updatedAt + "\""),
                "republish must not refresh updatedAt");
    }

    /** (16) soft-deleted question is invisible (detail 404, list excludes). */
    @Test
    void softDeletedQuestionIsInvisible() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        String body = createQuestion(token, spaceId, singleChoiceBody())
                .getResponse().getContentAsString();
        Long questionId = extractJsonLong(body, "id");
        jdbcTemplate.update("UPDATE question SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                questionId);

        mockMvc.perform(get(QUESTIONS + "/{questionId}", spaceId, questionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(QUESTIONS, spaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** (17) option row correctness is never stored — only in answer_data_json. */
    @Test
    void optionTableHasNoCorrectnessColumn() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        createQuestion(tokenFor("biz-e2e-user-1"), spaceId, singleChoiceBody());
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_option'");
        assertTrue(columns.stream().noneMatch(c -> String.valueOf(c.get("COLUMN_NAME"))
                        .toLowerCase().contains("correct")),
                "question_option must not carry correctness: " + columns);
    }

    // ==================== cleanup ====================

    private void cleanBizTestRows() {
        String placeholders = String.join(",", Collections.nCopies(BIZ_TEST_USERS.size(), "?"));
        Object[] users = BIZ_TEST_USERS.toArray();
        String spaceIds = "(SELECT id FROM learning_space WHERE owner_subject IN (" + placeholders + "))";
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
                "DELETE FROM knowledge_category WHERE space_id IN " + spaceIds + " AND parent_id IS NOT NULL", users);
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
                    "Refusing to run QuestionVerticalSliceIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
