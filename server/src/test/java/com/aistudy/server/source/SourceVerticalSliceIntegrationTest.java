package com.aistudy.server.source;

import com.aistudy.server.spike.auth.SpikeJwtTokenService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * BUSINESS-002 — Source vertical slice integration test.
 *
 * <p>Proves the production Source API end-to-end against a real
 * MySQL database (aistudy_flyway_test), with real Flyway migrations
 * (V005 included), real Spring Security, real HS256 JWTs, and NO
 * mocks:
 *
 * <pre>
 *   SpikeJwtTokenService.issueAccessToken(subject)   (JWT sub)
 *       ↓
 *   HTTP /api/v1/spaces/{spaceId}/sources[...]
 *       Authorization: Bearer ***
 *       ↓
 *   SecurityFilterChain → JwtDecoder → Authentication (getName() = sub)
 *       ↓
 *   SourceController → SourceService
 *       ↓  (parent ownership via LearningSpaceService.getMine = id+owner)
 *   SourceMapper (JOIN learning_space ... owner_subject = ?)
 *       ↓
 *   MySQL source table (V005, FK → learning_space)
 *       ↓
 *   HTTP 201 / 200 / 401 / 404 / 400
 * </pre>
 *
 * <h3>Coverage (task BUSINESS-002 B8)</h3>
 *
 * <ol>
 *   <li>user1 creates Source in own space → 201</li>
 *   <li>created Source really persisted (JdbcTemplate)</li>
 *   <li>user2 cannot create in user1's space → 404</li>
 *   <li>user1 lists own space → contains own sources</li>
 *   <li>user2 lists user1's space → 404</li>
 *   <li>user1 gets own source → 200</li>
 *   <li>user2 gets user1's source → 404</li>
 *   <li>cross-space IDOR: owner + spaceId B + sourceId of space A → 404</li>
 *   <li>anonymous GET → 401</li>
 *   <li>anonymous POST → 401</li>
 *   <li>blank title → 400</li>
 *   <li>invalid sourceType → 400</li>
 * </ol>
 *
 * <h3>Database safety</h3>
 *
 * <ul>
 *   <li>Schema guard: {@code SELECT DATABASE()} must equal
 *       {@code aistudy_flyway_test} exactly.</li>
 *   <li>{@code Flyway.migrate()} idempotent + {@code cleanDisabled(true)}.</li>
 *   <li>Cleanup order is FK-aware: DELETE source rows FIRST, then
 *       learning_space rows (V005 FK fk_source_space RESTRICT).</li>
 *   <li>Cleanup DELETE is scoped to biz-e2e users only; no
 *       unqualified DELETE / TRUNCATE / DROP.</li>
 * </ul>
 *
 * <h3>Mockito policy: NONE</h3>
 *
 * <p>All beans (SourceMapper, LearningSpaceMapper, services) are
 * real Spring beans under the {@code flyway-it} profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceVerticalSliceIntegrationTest {

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

    /** Inserts a LearningSpace fixture row owned by a biz user; returns its id. */
    private Long insertFixtureSpace(String ownerSubject, String name) {
        jdbcTemplate.update(
                "INSERT INTO learning_space "
                        + "(name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, ownerSubject);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space "
                        + "WHERE owner_subject = ? AND name = ? ORDER BY id DESC LIMIT 1",
                Long.class, ownerSubject, name);
        assertNotNull(id, "fixture space insert must produce an id");
        return id;
    }

    /** Inserts a Source fixture row directly (bypassing the API); returns its id. */
    private Long insertFixtureSource(Long spaceId, String title, String sourceType, String createdBy) {
        jdbcTemplate.update(
                "INSERT INTO source "
                        + "(space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'REGISTERED', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title, sourceType, createdBy);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM source "
                        + "WHERE space_id = ? AND title = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, title);
        assertNotNull(id, "fixture source insert must produce an id");
        return id;
    }

    /**
     * FK-aware scoped cleanup: sources first, then their parent
     * learning_space rows, both restricted to biz-e2e users.
     */
    private void cleanBizTestRows() {
        String placeholders = String.join(",",
                Collections.nCopies(BIZ_TEST_USERS.size(), "?"));
        jdbcTemplate.update(
                "DELETE FROM source WHERE space_id IN ("
                        + "SELECT id FROM learning_space WHERE owner_subject IN ("
                        + placeholders + "))",
                BIZ_TEST_USERS.toArray());
        jdbcTemplate.update(
                "DELETE FROM learning_space WHERE owner_subject IN (" + placeholders + ")",
                BIZ_TEST_USERS.toArray());
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run SourceVerticalSliceIntegrationTest: "
                            + "expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "BUSINESS-002 MUST run against " + EXPECTED_SCHEMA
                            + " only.");
        }
    }

    // ==================== tests ====================

    /** (1) user1 creates a Source in own space → 201 with typed body. */
    @Test
    void ownerCanCreateSourceInOwnSpace() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"数据库系统工程师教程\",\"sourceType\":\"DESKTOP_UPLOAD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.title").value("数据库系统工程师教程"))
                .andExpect(jsonPath("$.sourceType").value("DESKTOP_UPLOAD"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    /** (2) the created Source is really persisted in MySQL. */
    @Test
    void createdSourceIsPersistedInDatabase() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Java 核心技术\",\"sourceType\":\"DESKTOP_FOLDER_IMPORT\"}"))
                .andExpect(status().isCreated());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source "
                        + "WHERE space_id = ? AND title = ? AND source_type = ? AND status = 'REGISTERED' "
                        + "AND created_by_user_id = ?",
                Long.class, spaceId, "Java 核心技术", "DESKTOP_FOLDER_IMPORT", "biz-e2e-user-1");
        assertEquals(1L, count, "exactly one persisted source row expected");
    }

    /** (3) user2 cannot create a Source in user1's space → 404 (anti-probing). */
    @Test
    void otherUserCannotCreateSourceInMySpace() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"越权尝试\",\"sourceType\":\"DESKTOP_UPLOAD\"}"))
                .andExpect(status().isNotFound());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source WHERE space_id = ?", Long.class, spaceId);
        assertEquals(0L, count, "no source row may be written for a foreign space");
    }

    /** (4) user1 lists own space → contains the sources. */
    @Test
    void ownerListContainsOwnSources() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        insertFixtureSource(spaceId, "资料甲", "DESKTOP_UPLOAD", "biz-e2e-user-1");
        insertFixtureSource(spaceId, "资料乙", "ADMIN_MANUAL", "biz-e2e-user-1");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("资料乙"))
                .andExpect(jsonPath("$[1].title").value("资料甲"));
    }

    /** (5) user2 lists user1's space → 404 (parent not owned). */
    @Test
    void otherUserCannotListMySpaceSources() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (6) user1 gets own source → 200 with typed body. */
    @Test
    void ownerCanGetOwnSource() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "我的资料", "DESKTOP_UPLOAD", "biz-e2e-user-1");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}", spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sourceId))
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.title").value("我的资料"))
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    /** (7) user2 gets user1's source → 404 (JOIN collapses owner mismatch). */
    @Test
    void otherUserCannotGetMySource() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");
        Long sourceId = insertFixtureSource(spaceId, "user1 的资料", "DESKTOP_UPLOAD", "biz-e2e-user-1");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}", spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /**
     * (8) Cross-space IDOR: the caller OWNS both spaces, but the
     * sourceId belongs to space A while the path asks for space B →
     * 404. This proves the JOIN constrains source.space_id too, not
     * just the owner.
     */
    @Test
    void sourceIdFromAnotherSpaceIsRejected() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long sourceId = insertFixtureSource(spaceA, "属于A的资料", "DESKTOP_UPLOAD", "biz-e2e-user-1");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}", spaceB, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isNotFound());
    }

    /** (9) anonymous GET → 401. */
    @Test
    void anonymousGetReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/1/sources"))
                .andExpect(status().isUnauthorized());
    }

    /** (10) anonymous POST → 401 (CSRF-ignored Bearer path → entry point 401). */
    @Test
    void anonymousPostReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/spaces/1/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"匿名\",\"sourceType\":\"DESKTOP_UPLOAD\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** (11) blank title → 400 (Bean Validation @NotBlank). */
    @Test
    void blankTitleReturns400() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"sourceType\":\"DESKTOP_UPLOAD\"}"))
                .andExpect(status().isBadRequest());
    }

    /** (12) invalid sourceType → 400 (@Pattern against documented values). */
    @Test
    void invalidSourceTypeReturns400() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"合法标题\",\"sourceType\":\"BOGUS_TYPE\"}"))
                .andExpect(status().isBadRequest());
    }
}
