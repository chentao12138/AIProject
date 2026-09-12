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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
 * BUSINESS-005 — IngestionJob vertical slice integration test.
 *
 * <p>Real MySQL ({@code flyway-it} profile, schema guard against
 * {@code aistudy_flyway_test}), MockMvc + real JWT, and a TEMPORARY
 * storage root injected via {@link DynamicPropertySource}. Assets are
 * created through the real BUSINESS-004 upload API, then ingestion
 * jobs are created against them through the real BUSINESS-005 API.
 *
 * <h3>Coverage</h3>
 *
 * <p>Create for TXT asset → PENDING; create for valid ZIP → PENDING
 * (safety gate passed); create for traversal ZIP and corrupt ZIP →
 * FAILED with {@code ZIP_SAFETY_VIOLATION} + safe message (no stack
 * trace); DB row assertions; get/list owner + 404 matrix; retry
 * FAILED → PENDING (safety gate re-runs, retryCount++); retry on
 * non-FAILED → 409; missing assetId → 400; anonymous → 401.
 *
 * <h3>Database safety</h3>
 *
 * <ul>
 *   <li>Schema guard: {@code SELECT DATABASE()} must equal
 *       {@code aistudy_flyway_test} exactly.</li>
 *   <li>FK-aware cleanup order: ingestion_job → source_asset →
 *       source → learning_space (all scoped to biz-e2e users).</li>
 *   <li>No unqualified DELETE / TRUNCATE / DROP; no
 *       FOREIGN_KEY_CHECKS=0.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionJobIntegrationTest {

    /** The ONLY database this test is allowed to run against. */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /** Business-test users owned by this class; cleanup scoped to them. */
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1",
            "biz-e2e-user-2"
    );

    private static final String JOB_BASE =
            "/api/v1/spaces/{spaceId}/ingestion-jobs";
    /** Detail endpoint: JOB_BASE alone has no {jobId} placeholder —
     * using it for detail GETs silently requests the LIST route
     * (RUNTIME-FIX-03-A). */
    private static final String JOB_DETAIL =
            JOB_BASE + "/{jobId}";
    private static final String SOURCE_JOBS =
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
            storageRoot = Files.createTempDirectory("aistudy-job-it-");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp storage root", e);
        }
        registry.add("aistudy.storage.local.root", () -> storageRoot.toString());
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

    /** Creates an ingestion job through the BUSINESS-005 API; returns the job id. */
    private Long createJob(String token, Long spaceId, Long sourceId, Long assetId) throws Exception {
        MvcResult result = mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return extractJsonLong(result.getResponse().getContentAsString(), "id");
    }

    /**
     * FK-aware scoped cleanup, deepest first — the full dependency
     * chain including BUSINESS-006 content tables: TXT ingestion jobs
     * (SUCCEEDED) leave source_page + content_block rows that
     * reference source_asset, so those must be deleted BEFORE
     * source_asset (fk_source_page_asset / fk_content_block_page).
     * knowledge_point_source is included for completeness even though
     * this class never creates links (RUNTIME-FIX-02-A).
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
                    "Refusing to run IngestionJobIntegrationTest: "
                            + "expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "BUSINESS-005 MUST run against " + EXPECTED_SCHEMA
                            + " only.");
        }
    }

    private Map<String, Object> jobRowById(Long jobId) {
        return jdbcTemplate.queryForMap("SELECT * FROM ingestion_job WHERE id = ?", jobId);
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

    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] validZip() throws IOException {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("chapter1/", new byte[0]);
        entries.put("chapter1/1.txt", "hello".getBytes(StandardCharsets.UTF_8));
        entries.put("chapter1/2.md", "# title".getBytes(StandardCharsets.UTF_8));
        return zipBytes(entries);
    }

    private static byte[] traversalZip() throws IOException {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("../evil.txt", "boom".getBytes(StandardCharsets.UTF_8));
        return zipBytes(entries);
    }

    private static byte[] corruptZip() {
        return "PK\u0003\u0004 definitely not a real zip".getBytes(StandardCharsets.UTF_8);
    }

    // ==================== create: success path ====================

    /** (1) TXT asset → job created and EXECUTED (BUSINESS-006 wires TXT). */
    @Test
    void createForTextAssetExecutesToSucceeded() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", "hello txt".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.sourceId").value(sourceId))
                .andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stage").value("PUBLISHED"))
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    /** (2) valid ZIP asset → job PENDING (safety gate passed). */
    @Test
    void createForValidZipYieldsPendingJob() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教程包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "book.zip", "application/zip", validZip());

        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    /** (3) ZIP with a traversal entry → job FAILED with ZIP_SAFETY_VIOLATION. */
    @Test
    void createForTraversalZipFailsWithSafetyViolation() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "恶意包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "evil.zip", "application/zip", traversalZip());

        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ZIP_SAFETY_VIOLATION"))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty());
    }

    /** (4) unreadable ZIP bytes → job FAILED with ZIP_SAFETY_VIOLATION. */
    @Test
    void createForCorruptZipFailsWithSafetyViolation() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "损坏包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "broken.zip", "application/zip", corruptZip());

        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ZIP_SAFETY_VIOLATION"))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty());
    }

    /** (5) the job row really exists with all lifecycle fields (valid ZIP → PENDING). */
    @Test
    void jobRowPersistedWithLifecycleFields() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教程包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "book.zip", "application/zip", validZip());
        Long jobId = createJob(token, spaceId, sourceId, assetId);

        Map<String, Object> row = jobRowById(jobId);
        assertEquals(spaceId, ((Number) row.get("space_id")).longValue());
        assertEquals(sourceId, ((Number) row.get("source_id")).longValue());
        assertEquals(assetId, ((Number) row.get("asset_id")).longValue());
        assertEquals("PENDING", row.get("status"));
        assertEquals("QUEUED", row.get("stage"));
        assertEquals(0, ((Number) row.get("progress_percent")).intValue());
        assertEquals(0, ((Number) row.get("retry_count")).intValue());
        assertEquals("biz-e2e-user-1", row.get("created_by_user_id"));
        assertNull(row.get("started_at"), "PENDING job must not have started_at");
        assertNull(row.get("finished_at"), "PENDING job must not have finished_at");
        assertNotNull(row.get("created_at"));
        assertNotNull(row.get("updated_at"));
    }

    /** (6) failed job error_message is a SAFE diagnostic — no stack trace. */
    @Test
    void failedJobErrorMessageContainsNoStackTrace() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "恶意包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "evil.zip", "application/zip", traversalZip());

        String body = mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("traversal"), "message should describe the violation: " + body);
        assertTrue(!body.contains(" at com.aistudy"),
                "message must not leak a stack trace: " + body);
        assertTrue(!body.contains("Exception"), "message must not leak exception class names: " + body);
    }

    // ==================== create: validation / authorization ====================

    /** (7) missing assetId → 400. */
    @Test
    void missingAssetIdReturns400() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** (8) anonymous create → 401. */
    @Test
    void anonymousCreateReturns401() throws Exception {
        mockMvc.perform(post(SOURCE_JOBS, 1L, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    /** (9) other user's asset → 404 and NO job row. */
    @Test
    void otherUserCannotCreateJobForMyAsset() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "私有资料");
        String token1 = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token1, spaceId, sourceId,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingestion_job WHERE source_id = ?", Long.class, sourceId);
        assertEquals(0L, count, "404 must not persist any job row");
    }

    /** (10) asset of a source in ANOTHER space cannot be reached via this space path. */
    @Test
    void assetFromAnotherSpaceCannotBeUsed() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long sourceInB = insertFixtureSource(spaceB, "B 的资料");
        String token = tokenFor("biz-e2e-user-1");
        Long assetInB = uploadAsset(token, spaceB, sourceInB,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(SOURCE_JOBS, spaceA, sourceInB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetInB + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (11) asset under a different source than the path source → 404. */
    @Test
    void assetOfAnotherSourceCannotBeUsed() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceA = insertFixtureSource(spaceId, "资料A");
        Long sourceB = insertFixtureSource(spaceId, "资料B");
        String token = tokenFor("biz-e2e-user-1");
        Long assetInB = uploadAsset(token, spaceId, sourceB,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetInB + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (12) nonexistent asset id → 404. */
    @Test
    void nonexistentAssetReturns404() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");

        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":999999}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ==================== get ====================

    /** (13) owner reads own job. */
    @Test
    void ownerCanGetOwnJob() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        Long jobId = createJob(token, spaceId, sourceId, assetId);

        mockMvc.perform(get(JOB_DETAIL, spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    /** (14) other user cannot read my job → 404. */
    @Test
    void otherUserCannotGetMyJob() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        Long jobId = createJob(token, spaceId, sourceId, assetId);

        mockMvc.perform(get(JOB_DETAIL, spaceId, jobId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (15) job of another space via this space path → 404. */
    @Test
    void jobFromAnotherSpaceCannotBeRead() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long sourceInB = insertFixtureSource(spaceB, "B 的资料");
        String token = tokenFor("biz-e2e-user-1");
        Long assetInB = uploadAsset(token, spaceB, sourceInB,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        Long jobInB = createJob(token, spaceB, sourceInB, assetInB);

        mockMvc.perform(get(JOB_DETAIL, spaceA, jobInB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ==================== list ====================

    /** (16) owner lists source job history, newest first (one job per asset). */
    @Test
    void ownerListsSourceJobsNewestFirst() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetOne = uploadAsset(token, spaceId, sourceId,
                "note1.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        Long assetTwo = uploadAsset(token, spaceId, sourceId,
                "note2.txt", "text/plain", "y".getBytes(StandardCharsets.UTF_8));
        Long first = createJob(token, spaceId, sourceId, assetOne);
        Long second = createJob(token, spaceId, sourceId, assetTwo);

        mockMvc.perform(get(SOURCE_JOBS, spaceId, sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first));
    }

    /** (17) other user cannot list my source jobs → 404. */
    @Test
    void otherUserCannotListMySourceJobs() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");

        mockMvc.perform(get(SOURCE_JOBS, spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (18) owned source with no jobs → 200 empty list. */
    @Test
    void listEmptyForOwnedSource() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");

        mockMvc.perform(get(SOURCE_JOBS, spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ==================== retry ====================

    /** (19) FAILED job retried → PENDING (retryCount++), safety gate re-runs and fails again. */
    @Test
    void retryFailedJobResetsAndRerunsSafetyGate() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "恶意包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "evil.zip", "application/zip", traversalZip());

        String createBody = mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        Long jobId = extractJsonLong(createBody, "id");

        mockMvc.perform(post(JOB_BASE + "/{jobId}/retry", spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retryCount").value(1))
                .andExpect(jsonPath("$.errorCode").value("ZIP_SAFETY_VIOLATION"))
                .andExpect(jsonPath("$.status").value("FAILED"));

        Map<String, Object> row = jobRowById(jobId);
        assertEquals(1, ((Number) row.get("retry_count")).intValue());
        assertEquals("FAILED", row.get("status"));
        assertNotNull(row.get("finished_at"), "failed retry must set finished_at again");
    }

    /** (20) retry of a PENDING (non-FAILED) job → 409. */
    @Test
    void retryNonFailedJobReturns409() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        Long jobId = createJob(token, spaceId, sourceId, assetId);

        mockMvc.perform(post(JOB_BASE + "/{jobId}/retry", spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /** (21) retry of another user's job → 404. */
    @Test
    void retryOtherUsersJobReturns404() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        Long jobId = createJob(token, spaceId, sourceId, assetId);

        mockMvc.perform(post(JOB_BASE + "/{jobId}/retry", spaceId, jobId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }
}
