package com.aistudy.server.ingestion;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.testsupport.AsyncIngestionJobs;
import com.aistudy.server.testsupport.OwnedSpaceReset;
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
 * <p>Create for TXT asset → QUEUED then executed to its terminal status;
 * create for valid ZIP → QUEUED (safety gate passed); traversal ZIP and
 * corrupt ZIP → FAILED with {@code ZIP_SAFETY_VIOLATION} + safe message (no
 * stack trace); DB row assertions; get/list owner + 404 matrix; retry
 * FAILED → QUEUED (safety gate re-runs, retryCount++); retry on a non-FAILED
 * job → 409; missing assetId → 400; anonymous → 401.
 *
 * <h3>Ingestion is asynchronous</h3>
 *
 * <p>{@code IngestionJobService.submit()} hands the job to the worker pool
 * from an {@code afterCommit()} hook, so the create response reports QUEUED
 * and the pipeline finishes on another thread: every status expectation here
 * is waited for through {@link AsyncIngestionJobs} (architecture.md §6.2).
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

    /**
     * Terminal status of an ingestion whose assets all extracted, and the
     * stage it reports.
     *
     * <p>This class originally expected {@code SUCCEEDED}/{@code PUBLISHED}/
     * {@code PENDING}, which belong to the {@code PENDING/RUNNING/SUCCEEDED}
     * set architecture.md §6.2 records as retired. The only live vocabulary is
     * {@code IngestionJobService.IngestionStatus}, whose success terminal is
     * {@code NEEDS_REVIEW} at stage {@code REVIEW} and whose initial state is
     * {@code QUEUED} (see the {@code IngestionJob} lifecycle javadoc).
     */
    private static final String INGESTED = "NEEDS_REVIEW";
    private static final String INGESTED_STAGE = "REVIEW";

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
        OwnedSpaceReset.forSubjects(jdbcTemplate, BIZ_TEST_USERS);
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

        // The create response only ever reports QUEUED — the worker is
        // dispatched from afterCommit — so the lifecycle assertions below run
        // against the job read once it has reached its terminal status.
        long jobId = AsyncIngestionJobs.createAndAwaitJobId(mockMvc, token, spaceId, sourceId,
                assetId, INGESTED);

        mockMvc.perform(get(JOB_DETAIL, spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.sourceId").value(sourceId))
                .andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.status").value(INGESTED))
                .andExpect(jsonPath("$.stage").value(INGESTED_STAGE))
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    /** (2) valid ZIP asset → job QUEUED (safety gate passed, worker pending). */
    @Test
    void createForValidZipYieldsQueuedJob() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教程包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "book.zip", "application/zip", validZip());

        // QUEUED is the job's initial state and the only one the create
        // response can carry, so it is asserted on that response directly.
        mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
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

        long jobId = AsyncIngestionJobs.createAndAwaitJobId(mockMvc, token, spaceId, sourceId,
                assetId, "FAILED");

        mockMvc.perform(get(JOB_DETAIL, spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
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

        long jobId = AsyncIngestionJobs.createAndAwaitJobId(mockMvc, token, spaceId, sourceId,
                assetId, "FAILED");

        mockMvc.perform(get(JOB_DETAIL, spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ZIP_SAFETY_VIOLATION"))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty());
    }

    /** (5) the job row really exists with all lifecycle fields (valid ZIP → QUEUED). */
    @Test
    void jobRowPersistedWithLifecycleFields() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教程包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "book.zip", "application/zip", validZip());

        // The unstarted-job fields are asserted on the CREATE response, which
        // is the snapshot IngestionJobService.create() wrote: the worker is
        // dispatched from afterCommit, so reading the same fields back from the
        // row would race a claim that already moved them on.
        MvcResult created = mockMvc.perform(post(SOURCE_JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.stage").value("IMPORT"))
                .andExpect(jsonPath("$.progressPercent").value(0))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.startedAt").doesNotExist())
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andReturn();
        Long jobId = extractJsonLong(created.getResponse().getContentAsString(), "id");

        Map<String, Object> row = jobRowById(jobId);
        assertEquals(spaceId, ((Number) row.get("space_id")).longValue());
        assertEquals(sourceId, ((Number) row.get("source_id")).longValue());
        assertEquals(assetId, ((Number) row.get("asset_id")).longValue());
        assertEquals("biz-e2e-user-1", row.get("created_by_user_id"));
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

        String body = AsyncIngestionJobs.createAndAwait(mockMvc, token, spaceId, sourceId,
                assetId, "FAILED");

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
        AsyncIngestionJobs.awaitStatus(mockMvc, token, spaceId, jobId, INGESTED);

        mockMvc.perform(get(JOB_DETAIL, spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.status").value(INGESTED));
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

    /** (19) FAILED job retried → QUEUED (retryCount++), safety gate re-runs and fails again. */
    @Test
    void retryFailedJobResetsAndRerunsSafetyGate() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "恶意包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "evil.zip", "application/zip", traversalZip());

        long jobId = AsyncIngestionJobs.createAndAwaitJobId(mockMvc, token, spaceId, sourceId,
                assetId, "FAILED");

        // The retry response reports the re-queued job; the re-run reaches its
        // own terminal state afterwards, exactly like the first attempt.
        mockMvc.perform(post(JOB_BASE + "/{jobId}/retry", spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retryCount").value(1))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        AsyncIngestionJobs.awaitStatus(mockMvc, token, spaceId, jobId, "FAILED");
        mockMvc.perform(get(JOB_DETAIL, spaceId, jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ZIP_SAFETY_VIOLATION"));

        Map<String, Object> row = jobRowById(jobId);
        assertEquals(1, ((Number) row.get("retry_count")).intValue());
        assertEquals("FAILED", row.get("status"));
        assertNotNull(row.get("finished_at"), "failed retry must set finished_at again");
    }

    /** (20) retry of an ingested (non-FAILED) job → 409. */
    @Test
    void retryNonFailedJobReturns409() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        Long jobId = createJob(token, spaceId, sourceId, assetId);
        AsyncIngestionJobs.awaitStatus(mockMvc, token, spaceId, jobId, INGESTED);

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

    // ==================== F-12: ZIP entry idempotency ====================

    /**
     * (22) F-12 — a SECOND ingestion run over the same ZIP asset must
     * not duplicate source_asset rows or RAW bytes: extraction reuses
     * the (path, sha256) rows created by the first run.
     */
    @Test
    void zipReExtractionProducesNoDuplicateAssetRows() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教程包");
        String token = tokenFor("biz-e2e-user-1");
        Long assetId = uploadAsset(token, spaceId, sourceId,
                "book.zip", "application/zip", validZip());

        createJob(token, spaceId, sourceId, assetId); // run 1 extracts entries

        List<Map<String, Object>> firstRun = jdbcTemplate.queryForList(
                "SELECT id, storage_key, sha256, asset_version FROM source_asset "
                        + "WHERE source_id = ? AND original_relative_path IS NOT NULL "
                        + "ORDER BY original_relative_path, asset_version", sourceId);
        assertEquals(2, firstRun.size(), "validZip has two allowed entries");
        for (Map<String, Object> row : firstRun) {
            assertEquals(1, ((Number) row.get("asset_version")).intValue(),
                    "first run must record version 1");
        }

        createJob(token, spaceId, sourceId, assetId); // run 2 re-extracts the SAME package

        List<Map<String, Object>> secondRun = jdbcTemplate.queryForList(
                "SELECT id, storage_key, sha256, asset_version FROM source_asset "
                        + "WHERE source_id = ? AND original_relative_path IS NOT NULL "
                        + "ORDER BY original_relative_path, asset_version", sourceId);
        assertEquals(firstRun.size(), secondRun.size(),
                "re-extraction must add ZERO source_asset rows (F-12 idempotency)");
        for (int i = 0; i < firstRun.size(); i++) {
            assertEquals(firstRun.get(i).get("id"), secondRun.get(i).get("id"),
                    "the same rows are reused");
            assertEquals(firstRun.get(i).get("storage_key"), secondRun.get(i).get("storage_key"),
                    "the RAW bytes are reused, not re-stored");
        }
    }

    /**
     * (23) F-12 — same entry path with DIFFERENT bytes is an explicit
     * version 2 (supersede-and-retain), not a silent duplicate: the v1
     * row survives with its bytes, the v2 row carries the new hash.
     */
    @Test
    void sameZipPathDifferentBytesCreatesNewVersion() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教程包");
        String token = tokenFor("biz-e2e-user-1");

        Map<String, byte[]> v1 = new java.util.LinkedHashMap<>();
        v1.put("chapter1/1.txt", "hello".getBytes(StandardCharsets.UTF_8));
        Long zipOne = uploadAsset(token, spaceId, sourceId,
                "book.zip", "application/zip", zipBytes(v1));
        createJob(token, spaceId, sourceId, zipOne);

        Map<String, byte[]> v2 = new java.util.LinkedHashMap<>();
        v2.put("chapter1/1.txt", "changed content".getBytes(StandardCharsets.UTF_8));
        Long zipTwo = uploadAsset(token, spaceId, sourceId,
                "book-v2.zip", "application/zip", zipBytes(v2));
        createJob(token, spaceId, sourceId, zipTwo);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, asset_version, superseded_at, sha256 FROM source_asset "
                        + "WHERE source_id = ? AND original_relative_path = ? "
                        + "ORDER BY asset_version",
                sourceId, "chapter1/1.txt");
        assertEquals(2, rows.size(), "path kept both versions (supersede-and-retain)");
        assertEquals(1, ((Number) rows.get(0).get("asset_version")).intValue());
        assertEquals(2, ((Number) rows.get(1).get("asset_version")).intValue());
        assertNotNull(rows.get(0).get("superseded_at"), "v1 head must be stamped superseded");
        assertNull(rows.get(1).get("superseded_at"), "v2 is the active head");
        assertTrue(!rows.get(0).get("sha256").equals(rows.get(1).get("sha256")),
                "versions carry their distinct hashes");
    }
}
