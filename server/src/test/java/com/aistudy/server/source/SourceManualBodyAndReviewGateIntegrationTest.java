package com.aistudy.server.source;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.source.service.SourceService;
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

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-04 / F-05 write-side integration test: what the database actually holds
 * after an admin types a document and then reviews it.
 *
 * <h3>Why this one needs real MySQL</h3>
 *
 * <p>The unit tests can pin the transition matrix, but they cannot prove the
 * two claims this slice is about: that a typed body becomes rows the rest of
 * the chain reads, and that a review decision moves
 * {@code source.status} and not just {@code review_status}. Both are properties
 * of persisted data under real FKs ({@code content_block -> source_page ->
 * source -> learning_space}, {@code extraction_revision <-> source}), so they
 * are asserted with {@link JdbcTemplate} after HTTP, with no mocks.
 *
 * <h3>Database safety</h3>
 *
 * <p>Refuses to run outside {@code aistudy_flyway_test}; Flyway migrates the
 * live chain (V058 included); cleanup is FK-derived and scoped to
 * {@code biz-e2e-*} subjects via {@link OwnedSpaceReset}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceManualBodyAndReviewGateIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String ADMIN = "biz-e2e-admin-1";
    private static final String OUTSIDER = "biz-e2e-user-3";

    private static final String BODY = """
            # 数据库索引

            索引加快查询，代价是写入与存储。

            - B+ 树适合范围查询
            - 哈希适合等值查询

            ```sql
            CREATE INDEX idx_a ON t (c);
            ```
            """;

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
        cleanRows();
    }

    @AfterEach
    void cleanUp() {
        cleanRows();
    }

    // ==================== helpers ====================

    private String adminToken() {
        return jwtAccessTokenService.issueAccessToken(ADMIN, List.of("USER", "ADMIN"));
    }

    private String userToken(String subject) {
        return jwtAccessTokenService.issueAccessToken(subject, List.of("USER"));
    }

    private Long insertSpace(String ownerSubject, String name) {
        jdbcTemplate.update(
                "INSERT INTO learning_space (name, description, owner_subject, status,"
                        + " created_at, updated_at) VALUES (?, NULL, ?, 'ACTIVE',"
                        + " CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, ownerSubject);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject = ? AND name = ?"
                        + " ORDER BY id DESC LIMIT 1", Long.class, ownerSubject, name);
    }

    /** Registers a bare source the way the owner endpoint does, bypassing HTTP. */
    private Long insertSource(Long spaceId, String status) {
        jdbcTemplate.update(
                "INSERT INTO source (space_id, title, source_type, status, review_status,"
                        + " created_by_user_id, created_at, updated_at)"
                        + " VALUES (?, 'gate-fixture', 'DESKTOP_UPLOAD', ?, NULL, ?, "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, status, ADMIN);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source WHERE space_id = ? AND title = 'gate-fixture'"
                        + " ORDER BY id DESC LIMIT 1", Long.class, spaceId);
    }

    private void insertIssue(Long spaceId, Long sourceId, String severity, String status) {
        jdbcTemplate.update(
                "INSERT INTO ingestion_issue (space_id, source_id, ingestion_job_id,"
                        + " source_page_id, issue_type, severity, message, status, created_at)"
                        + " VALUES (?, ?, NULL, NULL, 'EXTRACTION_PARTIAL_FAILURE', ?, ?,"
                        + " ?, CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, severity, "partial extraction failure", status);
    }

    private String manualCreate(Long spaceId, String jsonBody) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/sources/manual")
                        .param("spaceId", String.valueOf(spaceId))
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andReturn().getResponse().getContentAsString();
    }

    private void cleanRows() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, List.of(ADMIN, OUTSIDER));
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(actual).as("SELECT DATABASE() must be the throwaway schema").isNotNull();
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("Refusing to run against '" + actual
                    + "'; expected '" + EXPECTED_SCHEMA + "'.");
        }
    }

    // ==================== F-04: a typed body really becomes content ====================

    /**
     * The headline defect: {@code createManual} ignored {@code content}, so an
     * admin document existed with no text anywhere. A body must produce one
     * BODY page, ordered blocks of the derived types, and a current revision
     * that both the page and the blocks belong to.
     */
    @Test
    void manualSourcePersistsPageBlocksAndRevisionForTheSubmittedBody() throws Exception {
        Long spaceId = insertSpace(ADMIN, "admin 手工空间");

        mockMvc.perform(post("/api/v1/admin/sources/manual")
                        .param("spaceId", String.valueOf(spaceId))
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"索引笔记\",\"content\":\""
                                + BODY.replace("\"", "\\\"").replace("\n", "\\n")
                                + "\",\"format\":\"MARKDOWN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("ADMIN_MANUAL"))
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW"));

        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM source WHERE space_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId);

        Map<String, Object> sourceRow = jdbcTemplate.queryForMap(
                "SELECT status, review_status, current_extraction_revision_id"
                        + " FROM source WHERE id = ?", sourceId);
        assertThat(sourceRow.get("current_extraction_revision_id"))
                .as("a manual source must point at the revision holding its body")
                .isNotNull();
        Long revisionId = ((Number) sourceRow.get("current_extraction_revision_id")).longValue();

        List<Map<String, Object>> pages = jdbcTemplate.queryForList(
                "SELECT page_type, order_status, extracted_text, extraction_revision_id"
                        + " FROM source_page WHERE source_id = ?", sourceId);
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).get("page_type")).isEqualTo("BODY");
        assertThat(pages.get(0).get("order_status")).isEqualTo("AUTO");
        assertThat(pages.get(0).get("extraction_revision_id")).isEqualTo(revisionId);
        assertThat(String.valueOf(pages.get(0).get("extracted_text")))
                .contains("数据库索引")
                .contains("哈希适合等值查询");

        List<String> blockTypes = jdbcTemplate.queryForList(
                "SELECT block_type FROM content_block WHERE source_id = ?"
                        + " ORDER BY sort_order ASC, id ASC", String.class, sourceId);
        assertThat(blockTypes)
                .as("block structure must come from the shared Markdown extractor")
                .containsExactly("HEADING", "PARAGRAPH", "LIST", "CODE");

        List<String> blockTexts = jdbcTemplate.queryForList(
                "SELECT normalized_text FROM content_block WHERE source_id = ?"
                        + " ORDER BY sort_order ASC, id ASC", String.class, sourceId);
        assertThat(String.join("\n", blockTexts))
                .contains("索引加快查询")
                .contains("B+ 树适合范围查询")
                .contains("CREATE INDEX idx_a ON t (c);");

        Long pageId = jdbcTemplate.queryForObject(
                "SELECT id FROM source_page WHERE source_id = ?", Long.class, sourceId);
        List<Map<String, Object>> blocks = jdbcTemplate.queryForList(
                "SELECT extraction_revision_id, source_page_id, locator_json"
                        + " FROM content_block WHERE source_id = ?", sourceId);
        for (Map<String, Object> block : blocks) {
            assertThat(block.get("extraction_revision_id")).isEqualTo(revisionId);
            assertThat(block.get("source_page_id")).isEqualTo(pageId);
            assertThat(String.valueOf(block.get("locator_json")))
                    .as("provenance line range is what KnowledgePoint citations cite")
                    .contains("\"lineStart\"");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM extraction_revision WHERE id = ?", String.class, revisionId))
                .isEqualTo("NEEDS_REVIEW");
    }

    /** A manual body must not need an asset: the page carries a NULL asset link. */
    @Test
    void manualBodyPagesHaveNoAssetAndStillBelongToTheCurrentRevision() throws Exception {
        Long spaceId = insertSpace(ADMIN, "admin 无附件空间");
        manualCreate(spaceId, "{\"title\":\"无附件\",\"content\":\"一段普通正文\",\"format\":\"TEXT\"}");

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_page sp JOIN source s ON s.id = sp.source_id"
                        + " WHERE s.space_id = ? AND sp.source_asset_id IS NULL"
                        + " AND sp.extraction_revision_id = s.current_extraction_revision_id",
                Long.class, spaceId);
        assertThat(count).isEqualTo(1L);
    }

    /** A blank body is rejected instead of storing an empty, reviewable source. */
    @Test
    void blankManualBodyIsRejectedWithoutLeavingASourceRow() throws Exception {
        Long spaceId = insertSpace(ADMIN, "admin 空正文");

        mockMvc.perform(post("/api/v1/admin/sources/manual")
                        .param("spaceId", String.valueOf(spaceId))
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"空\",\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source WHERE space_id = ?", Long.class, spaceId))
                .isZero();
    }

    /** ROLE_ADMIN is enforced on the transport, not just by convention. */
    @Test
    void nonAdminCannotCreateAManualSource() throws Exception {
        Long spaceId = insertSpace(OUTSIDER, "别人的空间");

        mockMvc.perform(post("/api/v1/admin/sources/manual")
                        .param("spaceId", String.valueOf(spaceId))
                        .header("Authorization", "Bearer " + userToken(OUTSIDER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"越权\",\"content\":\"正文\"}"))
                .andExpect(status().isForbidden());
    }

    // ==================== F-05: review is a state machine ====================

    /** The reported jump: nothing extracted, nothing reviewed, straight to live. */
    @Test
    void registeredSourceCannotBePublishedDirectly() throws Exception {
        Long spaceId = insertSpace(ADMIN, "gate 空间");
        Long sourceId = insertSource(spaceId, SourceService.STATUS_REGISTERED);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(SourceService.ERROR_ILLEGAL_REVIEW_TRANSITION));

        assertThat(statusOf(sourceId)).isEqualTo(SourceService.STATUS_REGISTERED);
    }

    /** An invented status is a client mistake: 400 before anything is written. */
    @Test
    void inventedReviewStatusIsRejectedAsValidationFailure() throws Exception {
        Long spaceId = insertSpace(ADMIN, "gate 空间");
        Long sourceId = insertSource(spaceId, SourceService.STATUS_NEEDS_REVIEW);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(statusOf(sourceId)).isEqualTo(SourceService.STATUS_NEEDS_REVIEW);
    }

    /**
     * Publishing is a no-go while a HIGH/OPEN blocker is unresolved, and the
     * distinct code tells the client why this 409 differs from a bad
     * transition. Resolving the issue is what unblocks it.
     */
    @Test
    void publishIsBlockedByUnresolvedHighSeverityIssueAndClearsAfterResolve() throws Exception {
        Long spaceId = insertSpace(ADMIN, "gate 空间");
        Long sourceId = insertSource(spaceId, SourceService.STATUS_NEEDS_REVIEW);
        insertIssue(spaceId, sourceId, "HIGH", "OPEN");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(SourceService.ERROR_PUBLISH_BLOCKED_BY_ISSUES));

        assertThat(statusOf(sourceId)).isEqualTo(SourceService.STATUS_NEEDS_REVIEW);

        // A MEDIUM row and an already-resolved HIGH row are not blockers.
        insertIssue(spaceId, sourceId, "MEDIUM", "OPEN");
        insertIssue(spaceId, sourceId, "HIGH", "RESOLVED");
        jdbcTemplate.update("UPDATE ingestion_issue SET status = 'RESOLVED'"
                + " WHERE source_id = ? AND severity = 'HIGH'", sourceId);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        assertThat(statusOf(sourceId)).isEqualTo(SourceService.STATUS_PUBLISHED);
    }

    /**
     * The write that used to be missing: {@code review_status} moved while
     * {@code status} stayed behind, so every rule keyed on
     * {@code status='PUBLISHED'} kept excluding content that had just been
     * approved.
     */
    @Test
    void publishingPersistsTheBusinessStatusNotOnlyTheReviewColumn() throws Exception {
        Long spaceId = insertSpace(ADMIN, "gate 空间");
        Long sourceId = insertSource(spaceId, SourceService.STATUS_NEEDS_REVIEW);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, review_status, reviewed_at, reviewed_by FROM source WHERE id = ?",
                sourceId);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("review_status")).isEqualTo("PUBLISHED");
        assertThat(row.get("reviewed_at")).isNotNull();
        assertThat(row.get("reviewed_by")).isEqualTo(ADMIN);
    }

    /** Documented choice: re-publishing an already published source succeeds. */
    @Test
    void republishingAPublishedSourceIsAnIdempotentSuccess() throws Exception {
        Long spaceId = insertSpace(ADMIN, "gate 空间");
        Long sourceId = insertSource(spaceId, SourceService.STATUS_PUBLISHED);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.reviewStatus").value("PUBLISHED"));
    }

    /** Un-publishing stops the content being served; a reason is mandatory. */
    @Test
    void rejectingWithoutReasonIsRefusedAndWithReasonDropsContentOutOfVisibility() throws Exception {
        Long spaceId = insertSpace(ADMIN, "gate 空间");
        Long sourceId = insertSource(spaceId, SourceService.STATUS_PUBLISHED);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"REJECTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"REJECTED\",\"rejectedReason\":\"公式抽取错误\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(statusOf(sourceId)).isEqualTo(SourceService.STATUS_REJECTED);
    }

    /** A manual source reaches live content through the same chain, no SQL edits. */
    @Test
    void manualSourceCanBePublishedAndItsContentStaysListable() throws Exception {
        Long spaceId = insertSpace(ADMIN, "admin 发布链路");
        manualCreate(spaceId, "{\"title\":\"链路\",\"content\":\"# 标题\\n\\n正文段落\\n\",\"format\":\"MARKDOWN\"}");
        Long sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM source WHERE space_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId);

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/sources/{sourceId}/review", spaceId, sourceId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        Long visibleBlocks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_block cb JOIN source s ON s.id = cb.source_id"
                        + " WHERE cb.source_id = ? AND s.status = 'PUBLISHED'"
                        + " AND cb.extraction_revision_id = s.current_extraction_revision_id",
                Long.class, sourceId);
        assertThat(visibleBlocks)
                .as("rows a published source serves under the pinned visibility rule")
                .isEqualTo(2L);
    }

    private String statusOf(Long sourceId) {
        return jdbcTemplate.queryForObject("SELECT status FROM source WHERE id = ?",
                String.class, sourceId);
    }
}
