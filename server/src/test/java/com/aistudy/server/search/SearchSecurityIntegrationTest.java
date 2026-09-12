package com.aistudy.server.search;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-022 security: LearningSpace isolation, WrongQuestion user
 * isolation, and Question correctness leakage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchSecurityIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String USER_A = "biz-search-sec-a";
    private static final String USER_B = "biz-search-sec-b";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    private Long spaceA;
    private Long spaceB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void prepare() {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        clean();
        spaceA = insertSpace(USER_A, "空间A");
        spaceB = insertSpace(USER_B, "空间B");
        tokenA = jwtAccessTokenService.issueAccessToken(USER_A);
        tokenB = jwtAccessTokenService.issueAccessToken(USER_B);
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    @Test
    void foreignSpaceSearchReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceB)
                        .param("q", "secret")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownSpaceSearchDoesNotLeakForeignData() throws Exception {
        insertSource(spaceA, "AlphaOnlySource");
        insertSource(spaceB, "BetaOnlySource");
        String body = search(spaceA, tokenA, "OnlySource");
        assertTrue(body.contains("AlphaOnlySource"), body);
        assertFalse(body.contains("BetaOnlySource"), body);
    }

    @Test
    void wrongQuestionIsScopedToCurrentUser() throws Exception {
        Long questionId = insertQuestion(spaceA, "SharedWrongStemQ?", "{\"correctOptionKey\":\"A\"}");
        insertWrongQuestion(USER_A, spaceA, questionId);
        insertWrongQuestion(USER_B, spaceA, questionId);

        String bodyA = search(spaceA, tokenA, "SharedWrongStemQ", "WRONG_QUESTION");
        assertTrue(bodyA.contains("\"type\":\"WRONG_QUESTION\""), bodyA);
        assertTrue(bodyA.contains("SharedWrongStemQ"), bodyA);

        // User B is not owner of space A — 404, no leak.
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceA)
                        .param("q", "SharedWrongStemQ")
                        .param("types", "WRONG_QUESTION")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // Same space owner A only sees own wrong-question rows (count=1).
        assertTrue(bodyA.contains("\"totalElements\":1"), bodyA);
    }

    @Test
    void questionSearchDoesNotLeakCorrectnessFields() throws Exception {
        insertQuestion(spaceA, "What is ACID?", "{\"correctOptionKey\":\"A\",\"referenceAnswer\":\"secret-key\"}");
        String body = search(spaceA, tokenA, "ACID", "QUESTION");
        assertTrue(body.contains("What is ACID"), body);
        assertFalse(body.toLowerCase().contains("answer_data"), body);
        assertFalse(body.contains("correctOptionKey"), body);
        assertFalse(body.contains("correctOptionKeys"), body);
        assertFalse(body.contains("correctBoolean"), body);
        assertFalse(body.contains("referenceAnswer"), body);
        assertFalse(body.contains("secret-key"), body);
        assertFalse(body.contains("explanation"), body);
    }

    private String search(Long spaceId, String token, String q) throws Exception {
        return search(spaceId, token, q, null);
    }

    private String search(Long spaceId, String token, String q, String types) throws Exception {
        var req = get("/api/v1/spaces/{spaceId}/search", spaceId)
                .param("q", q)
                .header("Authorization", "Bearer " + token);
        if (types != null) {
            req = req.param("types", types);
        }
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Long insertSpace(String owner, String name) {
        jdbcTemplate.update(
                "INSERT INTO learning_space (name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, owner);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, owner, name);
    }

    private Long insertSource(Long spaceId, String title) {
        jdbcTemplate.update(
                "INSERT INTO source (space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'DESKTOP_UPLOAD', 'REGISTERED', 'x', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source WHERE space_id=? AND title=? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, title);
    }

    private Long insertQuestion(Long spaceId, String stem, String answerJson) {
        jdbcTemplate.update(
                "INSERT INTO question (space_id, question_type, stem, answer_data_json, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, 'SINGLE_CHOICE', ?, ?, 'USER_CURATED', 'PUBLISHED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, stem, answerJson);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM question WHERE space_id=? AND stem=? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, stem);
    }

    private void insertWrongQuestion(String user, Long spaceId, Long questionId) {
        jdbcTemplate.update(
                "INSERT INTO wrong_question (user_subject, space_id, question_id, first_wrong_at, last_wrong_at, wrong_count, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                user, spaceId, questionId);
    }

    private void clean() {
        for (String user : new String[]{USER_A, USER_B}) {
            jdbcTemplate.update("DELETE FROM wrong_question WHERE user_subject=?", user);
        }
        for (String user : new String[]{USER_A, USER_B}) {
            String spaces = "(SELECT id FROM learning_space WHERE owner_subject=?)";
            jdbcTemplate.update("DELETE FROM wrong_question WHERE space_id IN " + spaces, user);
            jdbcTemplate.update("DELETE FROM content_block WHERE space_id IN " + spaces, user);
            jdbcTemplate.update("DELETE FROM source_page WHERE space_id IN " + spaces, user);
            jdbcTemplate.update("DELETE FROM source_asset WHERE space_id IN " + spaces, user);
            jdbcTemplate.update("DELETE FROM question WHERE space_id IN " + spaces, user);
            jdbcTemplate.update("DELETE FROM knowledge_point WHERE space_id IN " + spaces, user);
            jdbcTemplate.update("DELETE FROM source WHERE space_id IN " + spaces, user);
            jdbcTemplate.update("DELETE FROM learning_space WHERE owner_subject=?", user);
        }
    }

    private void assertSchema() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("expected " + EXPECTED_SCHEMA + " got " + actual);
        }
    }
}
