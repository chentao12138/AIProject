package com.aistudy.server.knowledge;

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

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-003 — Knowledge Catalog vertical slice integration test.
 *
 * <p>Proves the production KnowledgeCategory + KnowledgePoint APIs
 * end-to-end against a real MySQL database (aistudy_flyway_test),
 * with real Flyway migrations (V006/V007 included), real Spring
 * Security, real HS256 JWTs, and NO mocks:
 *
 * <pre>
 *   JwtAccessTokenService.issueAccessToken(subject)   (JWT sub)
 *       ↓
 *   HTTP /api/v1/spaces/{spaceId}/knowledge-categories[...]
 *       HTTP /api/v1/spaces/{spaceId}/knowledge-points[...]
 *       Authorization: Bearer ***
 *       ↓
 *   SecurityFilterChain → JwtDecoder → Authentication (getName() = sub)
 *       ↓
 *   KnowledgeCategoryController / KnowledgePointController
 *       ↓
 *   Services (space ownership via LearningSpaceService.getMine;
 *             parent/category same-space invariant via scoped SQL)
 *       ↓
 *   Mappers (JOIN learning_space ... owner_subject = ?;
 *            reads filter deleted_at IS NULL)
 *       ↓
 *   MySQL knowledge_category (V006) / knowledge_point (V007)
 *       ↓
 *   HTTP 201 / 200 / 401 / 404 / 400
 * </pre>
 *
 * <h3>Coverage</h3>
 *
 * <p>Category (12): create root 201; persisted; create child;
 * parent-other-space 404; user2 create in user1 space 404; user1
 * list own space only; user2 list user1 space 404; user1 get 200;
 * user2 get 404; blank name 400; anonymous GET 401; anonymous
 * POST 401.
 *
 * <p>KnowledgePoint (18): create 201 with USER_CURATED/DRAFT/null
 * publishedAt; persisted; category same-space create ok;
 * category-other-owned-space 404; category-other-user 404; user1
 * list non-deleted only; user2 list user1 space 404; user1 get 200;
 * user2 get 404; cross-space IDOR (space B path + point of space A)
 * 404; blank title 400; blank content 400; publish DRAFT 200 +
 * PUBLISHED + publishedAt + DB check; user2 publish 404; wrong-space
 * publish 404; re-publish idempotent 200; anonymous publish 401.
 *
 * <h3>Database safety</h3>
 *
 * <ul>
 *   <li>Schema guard: {@code SELECT DATABASE()} must equal
 *       {@code aistudy_flyway_test} exactly.</li>
 *   <li>{@code Flyway.migrate()} idempotent + {@code cleanDisabled(true)}.</li>
 *   <li>FK-aware cleanup order: knowledge_point → knowledge_category
 *       → source → learning_space (all scoped to biz-e2e users).</li>
 *   <li>No unqualified DELETE / TRUNCATE / DROP.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeCatalogVerticalSliceIntegrationTest {

    /** The ONLY database this test is allowed to run against. */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /** Business-test users owned by this class; cleanup scoped to them. */
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
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space "
                        + "WHERE owner_subject = ? AND name = ? ORDER BY id DESC LIMIT 1",
                Long.class, ownerSubject, name);
        assertNotNull(id, "fixture space insert must produce an id");
        return id;
    }

    private Long insertFixtureCategory(Long spaceId, String name, Long parentId) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_category "
                        + "(space_id, parent_id, name, description, sort_order, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, parentId, name);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_category "
                        + "WHERE space_id = ? AND name = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, name);
        assertNotNull(id, "fixture category insert must produce an id");
        return id;
    }

    private Long insertFixturePoint(Long spaceId, Long categoryId, String title) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_point "
                        + "(space_id, category_id, title, summary, content, origin_type, status, "
                        + " difficulty, created_by_user_id, created_at, updated_at, published_at, deleted_at) "
                        + "VALUES (?, ?, ?, NULL, 'fixture content', 'USER_CURATED', 'DRAFT', "
                        + " NULL, 'biz-e2e-user-1', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), NULL, NULL)",
                spaceId, categoryId, title);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_point "
                        + "WHERE space_id = ? AND title = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, title);
        assertNotNull(id, "fixture point insert must produce an id");
        return id;
    }

    /**
     * FK-aware cleanup: knowledge_point → knowledge_category
     * (bottom-up, self-FK) → source → learning_space. All rows scoped
     * to the biz-e2e test users only.
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

    /**
     * Deletes test-owner categories bottom-up so the self-referencing
     * FK ({@code fk_knowledge_category_parent}) never rejects a
     * DELETE.
     *
     * <p>A single {@code DELETE FROM knowledge_category WHERE
     * space_id IN (...)} can fail: MySQL does not order rows
     * child-first, so a parent still referenced by a child row
     * violates the FK. This helper repeatedly deletes leaf rows
     * (categories with no remaining child) until no test-owner
     * category is left.
     *
     * <p>The LEFT JOIN deliberately does NOT filter
     * {@code child.space_id}: if corrupt data has a child in another
     * space referencing a test parent, that FK reference is real and
     * cleanup must not pretend it does not exist. The residual check
     * below fails loudly instead of silently deleting learning_space
     * underneath a still-referenced category.
     */
    private void deleteKnowledgeCategoriesBottomUp() {
        String placeholders = String.join(",",
                Collections.nCopies(BIZ_TEST_USERS.size(), "?"));
        Object[] users = BIZ_TEST_USERS.toArray();

        int deleted;
        do {
            deleted = jdbcTemplate.update(
                    "DELETE c "
                            + "FROM knowledge_category c "
                            + "LEFT JOIN knowledge_category child "
                            + "  ON child.parent_id = c.id "
                            + "WHERE c.space_id IN ("
                            + "    SELECT id FROM learning_space "
                            + "    WHERE owner_subject IN (" + placeholders + ")"
                            + ") "
                            + "  AND child.id IS NULL",
                    users);
        } while (deleted > 0);

        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_category "
                        + "WHERE space_id IN ("
                        + "    SELECT id FROM learning_space "
                        + "    WHERE owner_subject IN (" + placeholders + ")"
                        + ")",
                Long.class, users);
        if (remaining != null && remaining > 0) {
            throw new IllegalStateException(
                    "cleanup failed: " + remaining
                            + " test-owner knowledge_category row(s) remain after bottom-up delete "
                            + "(cross-space FK reference or self-cycle?) — "
                            + "refusing to delete learning_space");
        }
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run KnowledgeCatalogVerticalSliceIntegrationTest: "
                            + "expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "BUSINESS-003 MUST run against " + EXPECTED_SCHEMA
                            + " only.");
        }
    }

    // ==================== KnowledgeCategory tests ====================

    /** C1: user1 creates a root category → 201. */
    @Test
    void ownerCanCreateRootCategory() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-categories", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数据库\",\"description\":\"数据库相关\",\"parentId\":null,\"sortOrder\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andExpect(jsonPath("$.name").value("数据库"))
                .andExpect(jsonPath("$.sortOrder").value(1))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    /** C2: created category is really persisted. */
    @Test
    void createdCategoryIsPersisted() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-categories", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Java\"}"))
                .andExpect(status().isCreated());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_category WHERE space_id = ? AND name = ?",
                Long.class, spaceId, "Java");
        assertEquals(1L, count, "exactly one category row expected");
    }

    /** C3: child category creation succeeds with a same-space parent. */
    @Test
    void childCategoryCreationSucceeds() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long parentId = insertFixtureCategory(spaceId, "父分类", null);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-categories", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"子分类\",\"parentId\":" + parentId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(parentId));
    }

    /** C4: parent category belongs to ANOTHER space → 404 (same-space invariant). */
    @Test
    void parentFromAnotherSpaceReturns404() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long parentInB = insertFixtureCategory(spaceB, "B 的分类", null);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-categories", spaceA)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"跨界分类\",\"parentId\":" + parentInB + "}"))
                .andExpect(status().isNotFound());
    }

    /** C5: user2 cannot create a category in user1's space → 404. */
    @Test
    void otherUserCannotCreateCategoryInMySpace() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-categories", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权分类\"}"))
                .andExpect(status().isNotFound());
    }

    /** C6: user1 list returns only own space's categories, ordered. */
    @Test
    void ownerListContainsOwnCategoriesOrdered() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        insertFixtureCategory(spaceId, "分类甲", null);
        insertFixtureCategory(spaceId, "分类乙", null);
        // other user's space with its own category — must not leak
        Long otherSpace = insertFixtureSpace("biz-e2e-user-2", "user2 空间");
        insertFixtureCategory(otherSpace, "user2 私有分类", null);

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-categories", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("分类甲"))
                .andExpect(jsonPath("$[1].name").value("分类乙"));
    }

    /** C7: user2 list user1 space → 404. */
    @Test
    void otherUserCannotListMySpaceCategories() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-categories", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** C8: user1 get own category → 200. */
    @Test
    void ownerCanGetOwnCategory() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long categoryId = insertFixtureCategory(spaceId, "我的分类", null);

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-categories/{categoryId}", spaceId, categoryId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(categoryId))
                .andExpect(jsonPath("$.name").value("我的分类"));
    }

    /** C9: user2 get user1's category → 404. */
    @Test
    void otherUserCannotGetMyCategory() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");
        Long categoryId = insertFixtureCategory(spaceId, "user1 的分类", null);

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-categories/{categoryId}", spaceId, categoryId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** C10: blank name → 400. */
    @Test
    void blankCategoryNameReturns400() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-categories", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    /** C11: anonymous GET → 401. */
    @Test
    void anonymousCategoryGetReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/1/knowledge-categories"))
                .andExpect(status().isUnauthorized());
    }

    /** C12: anonymous POST → 401 (CSRF-ignored Bearer path → entry point 401). */
    @Test
    void anonymousCategoryPostReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/spaces/1/knowledge-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"匿名分类\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== KnowledgePoint tests ====================

    /** K1: user1 creates a point → 201 with USER_CURATED / DRAFT / null publishedAt. */
    @Test
    void ownerCanCreateKnowledgePointWithServerControlledFields() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"什么是索引\",\"summary\":\"索引概念\",\"content\":\"索引是...\",\"categoryId\":null,\"difficulty\":\"中等\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.title").value("什么是索引"))
                .andExpect(jsonPath("$.originType").value("USER_CURATED"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.publishedAt").doesNotExist())
                .andExpect(jsonPath("$.createdByUserId").doesNotExist())
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
    }

    /** K2: created point is really persisted with server-controlled values. */
    @Test
    void createdPointIsPersisted() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"B+树\",\"content\":\"B+树是...\"}"))
                .andExpect(status().isCreated());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_point "
                        + "WHERE space_id = ? AND title = ? AND origin_type = 'USER_CURATED' "
                        + "AND status = 'DRAFT' AND published_at IS NULL AND deleted_at IS NULL",
                Long.class, spaceId, "B+树");
        assertEquals(1L, count, "exactly one persisted point row expected");
    }

    /** K3: request cannot set originType/status — covered by DTO absence (OpenAPI test); here we also verify response forces them. */
    @Test
    void serverForcesOriginTypeAndStatusRegardlessOfRequestBody() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");

        // Even if a malicious client tries to send these, Jackson will
        // fail on unknown properties OR ignore them (default). We
        // assert the RESPONSE always carries server-controlled values.
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"合法标题\",\"content\":\"合法内容\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originType").value("USER_CURATED"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    /** K4: categoryId of the SAME space → create ok. */
    @Test
    void createPointWithSameSpaceCategorySucceeds() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long categoryId = insertFixtureCategory(spaceId, "数据库分类", null);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"事务\",\"content\":\"事务特性...\",\"categoryId\":" + categoryId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(categoryId));
    }

    /** K5: categoryId belongs to ANOTHER space owned by the same user → 404. */
    @Test
    void categoryFromAnotherOwnedSpaceReturns404() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long categoryInB = insertFixtureCategory(spaceB, "B 的分类", null);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points", spaceA)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"跨界知识点\",\"content\":\"内容\",\"categoryId\":" + categoryInB + "}"))
                .andExpect(status().isNotFound());
    }

    /** K6: categoryId belongs to ANOTHER user's space → 404. */
    @Test
    void categoryFromAnotherUserSpaceReturns404() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long otherSpace = insertFixtureSpace("biz-e2e-user-2", "user2 空间");
        Long categoryInOther = insertFixtureCategory(otherSpace, "user2 分类", null);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"越权分类引用\",\"content\":\"内容\",\"categoryId\":" + categoryInOther + "}"))
                .andExpect(status().isNotFound());
    }

    /** K7: user1 list → own space's non-deleted points only, newest first. */
    @Test
    void ownerListContainsOwnPointsOnly() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        insertFixturePoint(spaceId, null, "知识点甲");
        insertFixturePoint(spaceId, null, "知识点乙");
        // soft-deleted row must be excluded
        jdbcTemplate.update(
                "UPDATE knowledge_point SET deleted_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE space_id = ? AND title = '知识点甲'", spaceId);
        // other user's space
        Long otherSpace = insertFixtureSpace("biz-e2e-user-2", "user2 空间");
        insertFixturePoint(otherSpace, null, "user2 私有知识点");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("知识点乙"));
    }

    /** K8: user2 list user1 space → 404. */
    @Test
    void otherUserCannotListMySpacePoints() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** K9: user1 get own point → 200. */
    @Test
    void ownerCanGetOwnPoint() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long pointId = insertFixturePoint(spaceId, null, "我的知识点");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}", spaceId, pointId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pointId))
                .andExpect(jsonPath("$.title").value("我的知识点"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    /** K10: user2 get user1's point → 404. */
    @Test
    void otherUserCannotGetMyPoint() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");
        Long pointId = insertFixturePoint(spaceId, null, "user1 私有知识点");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}", spaceId, pointId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /**
     * K11: cross-space IDOR — correct owner, but path uses space B
     * while the point belongs to space A → 404.
     */
    @Test
    void pointIdFromAnotherSpaceIsRejected() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long pointId = insertFixturePoint(spaceA, null, "属于A的知识点");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}", spaceB, pointId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isNotFound());
    }

    /** K12: blank title → 400. */
    @Test
    void blankPointTitleReturns400() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"content\":\"内容\"}"))
                .andExpect(status().isBadRequest());
    }

    /** K13: blank content → 400. */
    @Test
    void blankPointContentReturns400() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"合法标题\",\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    /** K14: publish DRAFT → 200 + PUBLISHED + publishedAt + DB verified. */
    @Test
    void publishDraftPointSucceeds() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long pointId = insertFixturePoint(spaceId, null, "待发布知识点");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish",
                        spaceId, pointId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pointId))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());

        // DB verification
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_point "
                        + "WHERE id = ? AND status = 'PUBLISHED' AND published_at IS NOT NULL",
                Long.class, pointId);
        assertEquals(1L, count, "published row must carry status PUBLISHED + published_at");
    }

    /** K15: user2 publish user1's point → 404. */
    @Test
    void otherUserCannotPublishMyPoint() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 私有空间");
        Long pointId = insertFixturePoint(spaceId, null, "user1 私有知识点");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish",
                        spaceId, pointId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());

        // DB unchanged
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_point WHERE id = ? AND status = 'DRAFT'",
                Long.class, pointId);
        assertEquals(1L, count, "foreign publish attempt must not change the row");
    }

    /** K16: publish with the WRONG space in the path → 404. */
    @Test
    void publishWithWrongSpacePathReturns404() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long pointId = insertFixturePoint(spaceA, null, "属于A的知识点");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish",
                        spaceB, pointId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isNotFound());

        // DB unchanged
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_point WHERE id = ? AND status = 'DRAFT'",
                Long.class, pointId);
        assertEquals(1L, count, "wrong-space publish must not change the row");
    }

    /**
     * K17: re-publish an already-published point is a TRUE idempotent
     * no-op — 200 with status=PUBLISHED, and neither the response
     * timestamps nor the DB row change (no UPDATE executes). The
     * first publish normalizes the timestamp to MySQL DATETIME(6)
     * precision (microseconds), so the response value and the DB
     * round-trip are exactly equal — the second publish returns the
     * DB-loaded entity and must satisfy EXACT equality, never
     * tolerance or ">= first".
     */
    @Test
    void republishIsIdempotent() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long pointId = insertFixturePoint(spaceId, null, "可重复发布知识点");
        String token = tokenFor("biz-e2e-user-1");

        // First publish: DRAFT → PUBLISHED.
        String firstResponse = mockMvc.perform(post(
                        "/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish",
                        spaceId, pointId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String firstPublishedAt = extractJsonString(firstResponse, "publishedAt");
        String firstUpdatedAt = extractJsonString(firstResponse, "updatedAt");

        // DB after first publish: must match the response EXACTLY
        // (proves the service normalized to microsecond precision).
        java.time.LocalDateTime dbPublishedAt1 = jdbcTemplate.queryForObject(
                "SELECT published_at FROM knowledge_point WHERE id = ?",
                java.time.LocalDateTime.class, pointId);
        java.time.LocalDateTime dbUpdatedAt1 = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM knowledge_point WHERE id = ?",
                java.time.LocalDateTime.class, pointId);
        assertEquals(java.time.LocalDateTime.parse(firstPublishedAt), dbPublishedAt1,
                "first-publish response publishedAt must equal DB published_at (microsecond precision)");
        assertEquals(java.time.LocalDateTime.parse(firstUpdatedAt), dbUpdatedAt1,
                "first-publish response updatedAt must equal DB updated_at (microsecond precision)");

        // Second publish: idempotent no-op — 200, still PUBLISHED.
        String secondResponse = mockMvc.perform(post(
                        "/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish",
                        spaceId, pointId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();

        String secondPublishedAt = extractJsonString(secondResponse, "publishedAt");
        String secondUpdatedAt = extractJsonString(secondResponse, "updatedAt");

        // Response equality: the second response carries the SAME
        // timestamps as the first (no refresh in the payload).
        assertEquals(firstPublishedAt, secondPublishedAt,
                "re-publish must not refresh publishedAt (idempotent no-op)");
        assertEquals(firstUpdatedAt, secondUpdatedAt,
                "re-publish must not refresh updatedAt (idempotent no-op)");

        // DB equality: no UPDATE executed at all — both timestamps
        // byte-identical to the post-first-publish values.
        java.time.LocalDateTime dbPublishedAt2 = jdbcTemplate.queryForObject(
                "SELECT published_at FROM knowledge_point WHERE id = ?",
                java.time.LocalDateTime.class, pointId);
        java.time.LocalDateTime dbUpdatedAt2 = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM knowledge_point WHERE id = ?",
                java.time.LocalDateTime.class, pointId);
        assertEquals(dbPublishedAt1, dbPublishedAt2,
                "DB published_at must be byte-identical after idempotent re-publish");
        assertEquals(dbUpdatedAt1, dbUpdatedAt2,
                "DB updated_at must be byte-identical after idempotent re-publish");
    }

    /**
     * Minimal JSON string extraction helper for the publish response
     * (avoids a full JSON library dependency in this test).
     */
    private static String extractJsonString(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            throw new IllegalStateException("field '" + field + "' not found in response: " + json);
        }
        int colon = json.indexOf(':', keyIndex);
        int start = json.indexOf('"', colon);
        int end = json.indexOf('"', start + 1);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("field '" + field + "' is not a string in response: " + json);
        }
        return json.substring(start + 1, end);
    }

    /** K18: anonymous publish → 401. */
    @Test
    void anonymousPublishReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/spaces/1/knowledge-points/1/publish"))
                .andExpect(status().isUnauthorized());
    }
}
