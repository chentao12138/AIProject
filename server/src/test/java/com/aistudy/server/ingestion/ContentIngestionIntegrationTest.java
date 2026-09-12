package com.aistudy.server.ingestion;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-006 — TXT/Markdown content ingestion vertical slice
 * integration test.
 *
 * <p>Real MySQL ({@code flyway-it} profile, schema guard against
 * {@code aistudy_flyway_test}), MockMvc + real JWT, and a TEMPORARY
 * storage root injected via {@link DynamicPropertySource}. Assets are
 * created through the real BUSINESS-004 upload API, ingestion jobs
 * through the real BUSINESS-005 API, and ingested content is read
 * back through the real BUSINESS-006 pages/content-blocks API.
 *
 * <h3>Coverage</h3>
 *
 * <p>TXT/MD ingestion → SUCCEEDED job + real source_page/content_block
 * rows with exact text, block types, order, locator line ranges;
 * invalid UTF-8 → FAILED ENCODING_ERROR with zero content rows; empty
 * doc → SUCCEEDED with zero blocks; oversize → FAILED
 * DOCUMENT_TOO_LARGE (1KB test limit); malformed PDF → job FAILED
 * INVALID_PDF / PDF_PARSE_FAILED; malformed PNG → job FAILED
 * INVALID_IMAGE; duplicate re-ingest → 409; FAILED allows new
 * attempt; retry re-runs pipeline; pages/blocks list + pageId filter +
 * IDOR matrix.
 *
 * <h3>Database safety</h3>
 *
 * <ul>
 *   <li>Schema guard: {@code SELECT DATABASE()} must equal
 *       {@code aistudy_flyway_test} exactly.</li>
 *   <li>FK-aware cleanup order: content_block → source_page →
 *       ingestion_job → source_asset → source → learning_space
 *       (all scoped to biz-e2e users).</li>
 *   <li>No unqualified DELETE / TRUNCATE / DROP; no
 *       FOREIGN_KEY_CHECKS=0.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentIngestionIntegrationTest {

    /** The ONLY database this test is allowed to run against. */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /** Business-test users owned by this class; cleanup scoped to them. */
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1",
            "biz-e2e-user-2"
    );

    /** Tiny text-ingestion limit so the oversize test needs no real MB file. */
    private static final long TEST_MAX_TEXT_BYTES = 1024L;

    private static final String BLOCKS =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/content-blocks";
    private static final String PAGES =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/pages";
    private static final String JOBS =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs";

    private static Path storageRoot;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        try {
            storageRoot = Files.createTempDirectory("aistudy-content-it-");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp storage root", e);
        }
        registry.add("aistudy.storage.local.root", () -> storageRoot.toString());
        registry.add("aistudy.ingestion.text.max-document-bytes",
                () -> TEST_MAX_TEXT_BYTES + "B");
    }

    @BeforeAll
    void guardTargetDatabase() {
        assertSchemaIsFlywayTest();
    }

    @AfterAll
    void removeTempStorageRoot() throws IOException {
        if (storageRoot != null && Files.exists(storageRoot)) {
            try (var walk = Files.walk(storageRoot)) {
                walk.sorted(Collections.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
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

    private Long insertFixtureSource(Long spaceId, String title) {
        jdbcTemplate.update(
                "INSERT INTO source "
                        + "(space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'DESKTOP_UPLOAD', 'REGISTERED', 'biz-e2e-user-1', "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM source "
                        + "WHERE space_id = ? AND title = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, title);
        assertNotNull(id, "fixture source insert must produce an id");
        return id;
    }

    /** Uploads a real asset through the BUSINESS-004 API; returns the asset id. */
    private Long uploadAsset(String token, Long spaceId, Long sourceId,
                             String fileName, String contentType, byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(new MockMultipartFile("file", fileName, contentType, bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return extractJsonLong(result.getResponse().getContentAsString(), "id");
    }

    /** Creates an ingestion job; expects 201 and returns the job body. */
    private String createJobBody(String token, Long spaceId, Long sourceId, Long assetId,
                                 String expectedStatus) throws Exception {
        return mockMvc.perform(post(JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    /** FK-aware scoped cleanup, deepest first. */
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
                    "Refusing to run ContentIngestionIntegrationTest: "
                            + "expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "BUSINESS-006 MUST run against " + EXPECTED_SCHEMA
                            + " only.");
        }
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

    private long countRows(String table, String spaceIdsSubquery, Object[] users) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE space_id IN " + spaceIdsSubquery,
                Long.class, users);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }

    // ==================== TXT ingestion ====================

    /** (1) TXT ingest → SUCCEEDED job + exact page/block rows. */
    @Test
    void txtIngestSucceedsAndPersistsPageAndBlocks() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        String content = "hello txt\nsecond line";
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));

        String body = createJobBody(token, spaceId, sourceId, assetId, "SUCCEEDED");
        Long jobId = extractJsonLong(body, "id");

        // job row: lifecycle complete
        Map<String, Object> job = jdbcTemplate.queryForMap(
                "SELECT * FROM ingestion_job WHERE id = ?", jobId);
        assertEquals("SUCCEEDED", job.get("status"));
        assertEquals("PUBLISHED", job.get("stage"));
        assertEquals(100, ((Number) job.get("progress_percent")).intValue());
        assertNotNull(job.get("started_at"));
        assertNotNull(job.get("finished_at"));

        // exactly one page: BODY, AUTO, pageOrder 1, exact extracted text
        List<Map<String, Object>> pages = rows(
                "SELECT * FROM source_page WHERE space_id = ? AND source_id = ?",
                spaceId, sourceId);
        assertEquals(1, pages.size());
        Map<String, Object> page = pages.get(0);
        assertEquals(assetId, ((Number) page.get("source_asset_id")).longValue());
        assertEquals(1, ((Number) page.get("page_order")).intValue());
        assertEquals("BODY", page.get("page_type"));
        assertEquals("AUTO", page.get("order_status"));
        assertNull(page.get("source_page_number"));
        assertEquals(content, page.get("extracted_text"));

        // one paragraph block with exact text + locator
        List<Map<String, Object>> blocks = rows(
                "SELECT * FROM content_block WHERE space_id = ? AND source_id = ? ORDER BY sort_order",
                spaceId, sourceId);
        assertEquals(1, blocks.size());
        Map<String, Object> block = blocks.get(0);
        assertEquals(page.get("id"), block.get("source_page_id"));
        assertEquals("PARAGRAPH", block.get("block_type"));
        assertEquals(0, ((Number) block.get("sort_order")).intValue());
        assertEquals(content, block.get("normalized_text"));
        assertTrue(((String) block.get("locator_json")).contains("\"lineStart\":1"));
        assertTrue(((String) block.get("locator_json")).contains("\"lineEnd\":2"));
    }

    /** (2) Markdown ingest → deterministic block types in document order. */
    @Test
    void markdownIngestProducesOrderedBlocks() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "Markdown 笔记");
        String token = tokenFor("biz-e2e-user-1");
        String md = "# Title\n"
                + "\n"
                + "intro\n"
                + "\n"
                + "- a\n"
                + "- b\n"
                + "\n"
                + "```java\n"
                + "int x = 1;\n"
                + "```\n";
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.md", "text/markdown", md.getBytes(StandardCharsets.UTF_8));

        createJobBody(token, spaceId, sourceId, assetId, "SUCCEEDED");

        List<Map<String, Object>> blocks = rows(
                "SELECT * FROM content_block WHERE space_id = ? AND source_id = ? ORDER BY sort_order",
                spaceId, sourceId);
        assertEquals(List.of("HEADING", "PARAGRAPH", "LIST", "CODE"),
                blocks.stream().map(b -> (String) b.get("block_type")).toList());
        assertEquals("Title", blocks.get(0).get("normalized_text"));
        assertEquals("intro", blocks.get(1).get("normalized_text"));
        assertEquals("- a\n- b", blocks.get(2).get("normalized_text"));
        assertEquals("int x = 1;", blocks.get(3).get("normalized_text"));
        assertEquals(0, ((Number) blocks.get(0).get("sort_order")).intValue());
        assertEquals(3, ((Number) blocks.get(3).get("sort_order")).intValue());
    }

    /** (3) invalid UTF-8 → FAILED ENCODING_ERROR, ZERO content rows. */
    @Test
    void invalidUtf8FailsJobWithEncodingError() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        byte[] bad = new byte[]{(byte) 0xC3, 0x28, 0x61, 0x62};
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "bad.txt", "text/plain", bad);

        String body = createJobBody(token, spaceId, sourceId, assetId, "FAILED");

        assertTrue(body.contains("\"errorCode\":\"ENCODING_ERROR\""), body);
        assertTrue(body.contains("UTF-8"), "safe message must be actionable: " + body);
        assertTrue(!body.contains(" at com.aistudy"), "no stack trace: " + body);

        String spaceIds = "(SELECT id FROM learning_space WHERE owner_subject = 'biz-e2e-user-1')";
        assertEquals(0L, countRows("content_block", spaceIds, new Object[]{}),
                "failed job must leave zero content blocks");
        assertEquals(0L, countRows("source_page", spaceIds, new Object[]{}),
                "failed job must leave zero pages");
    }

    /**
     * (4) a semantically-empty document (whitespace-only, non-zero
     * upload) → SUCCEEDED with a page and zero blocks. Deliberately
     * NOT a 0-byte upload: BUSINESS-004 contract rejects empty
     * multipart files with 400, and that contract stays
     * (RUNTIME-FIX-02-C).
     */
    @Test
    void whitespaceOnlyDocumentSucceedsWithZeroBlocks() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "空文件");
        String token = tokenFor("biz-e2e-user-1");
        String whitespace = "   \n\n";
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "blank.txt", "text/plain", whitespace.getBytes(StandardCharsets.UTF_8));

        createJobBody(token, spaceId, sourceId, assetId, "SUCCEEDED");

        List<Map<String, Object>> pages = rows(
                "SELECT * FROM source_page WHERE space_id = ? AND source_id = ?",
                spaceId, sourceId);
        assertEquals(1, pages.size());
        assertEquals(whitespace, pages.get(0).get("extracted_text"));
        List<Map<String, Object>> blocks = rows(
                "SELECT * FROM content_block WHERE space_id = ? AND source_id = ?",
                spaceId, sourceId);
        assertEquals(0, blocks.size(), "whitespace-only document must produce zero blocks");
    }

    /** (5) document over the text limit → FAILED DOCUMENT_TOO_LARGE, zero rows. */
    @Test
    void oversizedDocumentFailsWithDocumentTooLarge() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "大文件");
        String token = tokenFor("biz-e2e-user-1");
        byte[] big = new byte[(int) TEST_MAX_TEXT_BYTES + 100];
        java.util.Arrays.fill(big, (byte) 'x');
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "big.txt", "text/plain", big);

        String body = createJobBody(token, spaceId, sourceId, assetId, "FAILED");

        assertTrue(body.contains("\"errorCode\":\"DOCUMENT_TOO_LARGE\""), body);
        String spaceIds = "(SELECT id FROM learning_space WHERE owner_subject = 'biz-e2e-user-1')";
        assertEquals(0L, countRows("content_block", spaceIds, new Object[]{}));
        assertEquals(0L, countRows("source_page", spaceIds, new Object[]{}));
    }

    // ==================== format gate / duplicate guard ====================

    /** (6) malformed PDF is accepted by create, then fails the PDF pipeline. */
    @Test
    void pdfAssetCreateFailsPipelineWithParseError() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教材");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "book.pdf", "application/pdf", "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));

        String body = createJobBody(token, spaceId, sourceId, assetId, "FAILED");
        assertTrue(body.contains("\"errorCode\":\"PDF_PARSE_FAILED\"")
                        || body.contains("\"errorCode\":\"INVALID_PDF\""),
                "malformed PDF must fail with a PDF error code, body=" + body);

        Long pageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_page WHERE source_id = ?", Long.class, sourceId);
        assertEquals(0L, pageCount, "failed PDF must not persist pages");
    }

    /** (7) malformed PNG is accepted by create, then fails the image pipeline. */
    @Test
    void imageAssetCreateFailsPipelineWithInvalidImage() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "图片");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "page.png", "image/png", new byte[]{1, 2, 3});

        String body = createJobBody(token, spaceId, sourceId, assetId, "FAILED");
        assertTrue(body.contains("\"errorCode\":\"INVALID_IMAGE\""),
                "malformed PNG must fail with INVALID_IMAGE, body=" + body);

        Long pageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_page WHERE source_id = ?", Long.class, sourceId);
        assertEquals(0L, pageCount, "failed image must not persist pages");
    }

    /** (8) re-ingesting a SUCCEEDED asset → 409, no duplicate content. */
    @Test
    void duplicateCreateOnSucceededAssetReturns409() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", "once".getBytes(StandardCharsets.UTF_8));
        createJobBody(token, spaceId, sourceId, assetId, "SUCCEEDED");

        mockMvc.perform(post(JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        List<Map<String, Object>> blocks = rows(
                "SELECT * FROM content_block WHERE space_id = ? AND source_id = ?",
                spaceId, sourceId);
        assertEquals(1, blocks.size(), "no duplicate content blocks on 409");
    }

    /** (9) a FAILED job does NOT block a fresh attempt. */
    @Test
    void failedJobAllowsFreshCreate() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        byte[] bad = new byte[]{(byte) 0xC3, 0x28, 0x61};
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "bad.txt", "text/plain", bad);
        createJobBody(token, spaceId, sourceId, assetId, "FAILED");

        String body = createJobBody(token, spaceId, sourceId, assetId, "FAILED");
        assertTrue(body.contains("\"retryCount\":0"), "fresh create starts at retryCount 0: " + body);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingestion_job WHERE source_id = ?", Long.class, sourceId);
        assertEquals(2L, count, "failed + fresh attempt = 2 job rows (history)");
    }

    /** (10) retry re-runs the text pipeline on a FAILED job. */
    @Test
    void retryFailedTextJobRerunsPipeline() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        byte[] bad = new byte[]{(byte) 0xC3, 0x28, 0x61};
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "bad.txt", "text/plain", bad);
        String body = createJobBody(token, spaceId, sourceId, assetId, "FAILED");
        Long jobId = extractJsonLong(body, "id");

        mockMvc.perform(post("/api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}/retry",
                        spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retryCount").value(1))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ENCODING_ERROR"));
    }

    // ==================== authorization ====================

    /** (11) other user cannot ingest my asset → 404. */
    @Test
    void otherUserCannotCreateJobForMyAsset() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "私有资料");
        String token1 = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token1, spaceId, sourceId,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (12) asset of another owned space cannot be reached via this space path. */
    @Test
    void assetFromAnotherSpaceCannotBeIngested() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long sourceInB = insertFixtureSource(spaceB, "B 的资料");
        String token = tokenFor("biz-e2e-user-1");
        Long assetInB = uploadAsset(token, spaceB, sourceInB,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(JOBS, spaceA, sourceInB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetInB + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ==================== content read API ====================

    /** (13) owner lists ingested pages. */
    @Test
    void ownerListsIngestedPages() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        String content = "page text";
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));
        createJobBody(token, spaceId, sourceId, assetId, "SUCCEEDED");

        mockMvc.perform(get(PAGES, spaceId, sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pageOrder").value(1))
                .andExpect(jsonPath("$[0].pageType").value("BODY"))
                .andExpect(jsonPath("$[0].orderStatus").value("AUTO"))
                .andExpect(jsonPath("$[0].extractedText").value(content));
    }

    /** (14) other user cannot list my pages → 404. */
    @Test
    void otherUserCannotListMyPages() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");

        mockMvc.perform(get(PAGES, spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (15) owner lists blocks in document order. */
    @Test
    void ownerListsBlocksInDocumentOrder() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "MD 笔记");
        String token = tokenFor("biz-e2e-user-1");
        String md = "# T\n\np1\n\np2\n";
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "n.md", "text/markdown", md.getBytes(StandardCharsets.UTF_8));
        createJobBody(token, spaceId, sourceId, assetId, "SUCCEEDED");

        mockMvc.perform(get(BLOCKS, spaceId, sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].blockType").value("HEADING"))
                .andExpect(jsonPath("$[0].normalizedText").value("T"))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].blockType").value("PARAGRAPH"))
                .andExpect(jsonPath("$[2].blockType").value("PARAGRAPH"))
                .andExpect(jsonPath("$[2].sortOrder").value(2))
                .andExpect(jsonPath("$[0].locatorJson").isNotEmpty());
    }

    /** (16) blocks filtered by pageId of the same source. */
    @Test
    void blocksFilteredByOwnPageId() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", "x\ny".getBytes(StandardCharsets.UTF_8));
        createJobBody(token, spaceId, sourceId, assetId, "SUCCEEDED");

        Long pageId = jdbcTemplate.queryForObject(
                "SELECT id FROM source_page WHERE space_id = ? AND source_id = ?",
                Long.class, spaceId, sourceId);

        mockMvc.perform(get(BLOCKS, spaceId, sourceId)
                        .param("pageId", String.valueOf(pageId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourcePageId").value(pageId));
    }

    /** (17) pageId from another source is a filter — empty list, no leak. */
    @Test
    void pageIdFromAnotherSourceYieldsEmptyList() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceA = insertFixtureSource(spaceId, "资料A");
        Long sourceB = insertFixtureSource(spaceId, "资料B");
        String token = tokenFor("biz-e2e-user-1");
        Long assetA = uploadAsset(token, spaceId, sourceA,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        createJobBody(token, spaceId, sourceA, assetA, "SUCCEEDED");
        Long pageInA = jdbcTemplate.queryForObject(
                "SELECT id FROM source_page WHERE space_id = ? AND source_id = ?",
                Long.class, spaceId, sourceA);

        mockMvc.perform(get(BLOCKS, spaceId, sourceB)
                        .param("pageId", String.valueOf(pageInA))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** (18) other user cannot list my blocks → 404. */
    @Test
    void otherUserCannotListMyBlocks() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");

        mockMvc.perform(get(BLOCKS, spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (19) anonymous reads → 401. */
    @Test
    void anonymousReadsReturn401() throws Exception {
        mockMvc.perform(get(BLOCKS, 1L, 1L)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PAGES, 1L, 1L)).andExpect(status().isUnauthorized());
    }

    /** (20) locator_json carries exact 1-based line ranges for every block. */
    @Test
    void locatorJsonHasExactLineRanges() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "MD");
        String token = tokenFor("biz-e2e-user-1");
        String md = "a\nb\n\nc";
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "n.md", "text/markdown", md.getBytes(StandardCharsets.UTF_8));
        createJobBody(token, spaceId, sourceId, assetId, "SUCCEEDED");

        List<Map<String, Object>> blocks = rows(
                "SELECT block_type, normalized_text, locator_json FROM content_block "
                        + "WHERE space_id = ? AND source_id = ? ORDER BY sort_order",
                spaceId, sourceId);
        assertEquals(2, blocks.size());
        assertTrue(((String) blocks.get(0).get("locator_json")).contains("\"lineStart\":1"));
        assertTrue(((String) blocks.get(0).get("locator_json")).contains("\"lineEnd\":2"));
        assertTrue(((String) blocks.get(1).get("locator_json")).contains("\"lineStart\":4"));
        assertTrue(((String) blocks.get(1).get("locator_json")).contains("\"lineEnd\":4"));
    }
}
