package com.aistudy.server.space;

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

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-001 — LearningSpace vertical slice integration test.
 *
 * <p>Proves the production LearningSpace API end-to-end against a
 * real MySQL database (aistudy_flyway_test), with real Flyway
 * migrations (V004 included), real Spring Security, real HS256 JWTs
 * issued by {@link SpikeJwtTokenService}, and NO mocks anywhere in
 * the path:
 *
 * <pre>
 *   SpikeJwtTokenService.issueAccessToken(...)   (subject → JWT sub)
 *       ↓
 *   HTTP /api/v1/spaces[ /{spaceId}]
 *       Authorization: Bearer ***
 *       ↓
 *   SecurityFilterChain → JwtDecoder → Authentication (getName() = sub)
 *       ↓
 *   LearningSpaceController → LearningSpaceService
 *       ↓
 *   LearningSpaceMapper (owner-scoped SQL: WHERE owner_subject = ?)
 *       ↓
 *   MySQL learning_space table (V004)
 *       ↓
 *   HTTP 201 / 200 / 401 / 404 / 400
 * </pre>
 *
 * <h3>Ten assertions covered</h3>
 *
 * <ol>
 *   <li>authenticated create → 201 + typed body</li>
 *   <li>created space is really persisted in MySQL (JdbcTemplate
 *       count + column check)</li>
 *   <li>user1's spaces appear in user1's list</li>
 *   <li>user1's spaces do NOT appear in user2's list</li>
 *   <li>user1 can GET their own space → 200</li>
 *   <li>user2 GET user1's space → 404 (absent/not-owned collapse)</li>
 *   <li>anonymous call → 401</li>
 *   <li>blank name → 400</li>
 *   <li>OpenAPI paths — covered by {@code LearningSpaceOpenApiContractTest}</li>
 *   <li>typed response schema — covered by
 *       {@code LearningSpaceOpenApiContractTest}</li>
 * </ol>
 *
 * <h3>Database safety</h3>
 *
 * <ul>
 *   <li>Schema guard: {@code SELECT DATABASE()} must equal
 *       {@code aistudy_flyway_test} exactly (equals only, never
 *       contains/startsWith/endsWith).</li>
 *   <li>{@code Flyway.migrate()} runs idempotently in
 *       {@code @BeforeEach} with {@code cleanDisabled(true)} —
 *       no clean() anywhere.</li>
 *   <li>Cleanup DELETE is scoped to this test class's OWN
 *       business-test users ({@code biz-e2e-user-1},
 *       {@code biz-e2e-user-2}); no unqualified DELETE, no TRUNCATE,
 *       no DROP.</li>
 *   <li>Fixtures use {@code CURRENT_TIMESTAMP(6)}.</li>
 * </ul>
 *
 * <h3>Mockito policy: NONE</h3>
 *
 * <p>No {@code @MockitoBean}, no {@code @Mock}, no stubbing. The
 * real {@code LearningSpaceMapper} bean is wired by MyBatis-Plus
 * auto-configuration under the {@code flyway-it} profile and
 * queries the real table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LearningSpaceVerticalSliceIntegrationTest {

    /** The ONLY database this test is allowed to run against. */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /** Business-test users owned by this class; cleanup is scoped to them. */
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1",
            "biz-e2e-user-2"
    );

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

    /**
     * Inserts a LearningSpace fixture row directly via JdbcTemplate
     * (bypassing the API so list/get isolation tests control the DB
     * state precisely). Returns the generated id.
     */
    private Long insertFixtureSpace(String ownerSubject, String name, String description) {
        jdbcTemplate.update(
                "INSERT INTO learning_space "
                        + "(name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, description, ownerSubject);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space "
                        + "WHERE owner_subject = ? AND name = ? ORDER BY id DESC LIMIT 1",
                Long.class, ownerSubject, name);
        assertNotNull(id, "fixture insert must produce an id");
        return id;
    }

    /**
     * FK-aware scoped cleanup: source_asset / source first (V005/V008
     * FKs — defensive even though this class only creates
     * learning_space rows, so a leftover from another test class
     * sharing the biz-e2e users cannot break the parent delete),
     * then learning_space, all restricted to biz-e2e users.
     */
    private void cleanBizTestRows() {
        String placeholders = String.join(",",
                Collections.nCopies(BIZ_TEST_USERS.size(), "?"));
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
        // knowledge_category is self-referencing (parent_id FK): delete
        // every non-root row first, then the remaining roots — correct
        // for any nesting depth in one pass.
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
                    "Refusing to run LearningSpaceVerticalSliceIntegrationTest: "
                            + "expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "BUSINESS-001 MUST run against " + EXPECTED_SCHEMA
                            + " only.");
        }
    }

    // ==================== tests ====================

    /**
     * (1) An authenticated user creates a LearningSpace → HTTP 201
     * with a typed body: id, name, description, status=ACTIVE,
     * createdAt, updatedAt. ownerSubject is NOT echoed back.
     */
    @Test
    void authenticatedUserCanCreateLearningSpace() throws Exception {
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数据库管理\",\"description\":\"数据库系统工程师教程学习\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("数据库管理"))
                .andExpect(jsonPath("$.description").value("数据库系统工程师教程学习"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    /**
     * (2) The space created through the API is REALLY persisted in
     * MySQL: a JdbcTemplate query sees exactly one row with the
     * expected owner/status.
     */
    @Test
    void createdSpaceIsPersistedInDatabase() throws Exception {
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Java 学习\",\"description\":null}"))
                .andExpect(status().isCreated());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_space "
                        + "WHERE owner_subject = ? AND name = ? AND status = 'ACTIVE'",
                Long.class, "biz-e2e-user-1", "Java 学习");
        assertEquals(1L, count, "exactly one persisted row expected");
    }

    /**
     * (3) user1's own spaces appear in user1's list.
     */
    @Test
    void ownerListContainsOwnSpaces() throws Exception {
        insertFixtureSpace("biz-e2e-user-1", "空间甲", "user1 的空间");
        insertFixtureSpace("biz-e2e-user-1", "空间乙", null);

        mockMvc.perform(get("/api/v1/spaces")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("空间乙"))
                .andExpect(jsonPath("$[1].name").value("空间甲"))
                .andExpect(jsonPath("$[0].ownerSubject").doesNotExist());
    }

    /**
     * (4) user2's list must NOT contain user1's spaces. With zero
     * spaces of his own, user2 gets an empty list.
     */
    @Test
    void otherUserListDoesNotContainMySpaces() throws Exception {
        insertFixtureSpace("biz-e2e-user-1", "user1 私有空间", null);

        mockMvc.perform(get("/api/v1/spaces")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * (5) user1 can GET their own space → 200 with the typed body.
     */
    @Test
    void ownerCanGetOwnSpace() throws Exception {
        Long id = insertFixtureSpace("biz-e2e-user-1", "我的空间", "owner 可见");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}", id)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("我的空间"))
                .andExpect(jsonPath("$.description").value("owner 可见"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /**
     * (6) user2 GET user1's space → 404 (absent/not-owned collapse;
     * api-guidelines.md §13: 404 = "不存在/按安全策略不可见").
     * Also: a completely unknown space id → 404 for the owner too.
     */
    @Test
    void otherUserCannotGetMySpace() throws Exception {
        Long id = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间", null);

        mockMvc.perform(get("/api/v1/spaces/{spaceId}", id)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());

        // Owner asking for a non-existent id is also 404 (same code path).
        mockMvc.perform(get("/api/v1/spaces/{spaceId}", 999999999L)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isNotFound());
    }

    /**
     * (7) Anonymous call to the production API → 401 (existing
     * SecurityFilterChain boundary; no token at all).
     */
    @Test
    void anonymousCallerGets401() throws Exception {
        mockMvc.perform(get("/api/v1/spaces"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"匿名空间\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * (8) Blank name → 400 (Bean Validation @NotBlank on
     * CreateLearningSpaceRequest.name).
     */
    @Test
    void blankNameReturns400() throws Exception {
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"description\":null}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"no name\"}"))
                .andExpect(status().isBadRequest());
    }
}
