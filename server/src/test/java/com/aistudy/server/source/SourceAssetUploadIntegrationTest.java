package com.aistudy.server.source;

import com.aistudy.server.spike.auth.SpikeJwtTokenService;
import com.aistudy.server.storage.StorageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-004 — SourceAsset upload vertical slice integration test.
 *
 * <p>Real MySQL ({@code flyway-it} profile, schema guard against
 * {@code aistudy_flyway_test}), MockMvc + real JWT, and a TEMPORARY
 * storage root injected via {@link DynamicPropertySource} — never
 * {@code D:\AIStudyData} or any real location.
 *
 * <h3>Coverage (22)</h3>
 *
 * <p>Upload success path (201 + DB row + storage round-trip + size +
 * sha256), ZIP → ORIGINAL_PACKAGE, non-ZIP → ORIGINAL_FILE, 415
 * (unsupported extension, MIME mismatch), 400 (empty), 413 (over the
 * tiny test limit), 404 ×4 (other user, other owned space, wrong
 * source, wrong space), 401 anonymous, list/get owner + 404 matrix,
 * {@code C:\fakepath\book.pdf} → {@code book.pdf} basename, and the
 * response must NOT expose storageKey.
 *
 * <h3>Database safety</h3>
 *
 * <ul>
 *   <li>Schema guard: {@code SELECT DATABASE()} must equal
 *       {@code aistudy_flyway_test} exactly.</li>
 *   <li>FK-aware cleanup order: source_asset → source →
 *       learning_space (all scoped to biz-e2e users).</li>
 *   <li>No unqualified DELETE / TRUNCATE / DROP; no
 *       FOREIGN_KEY_CHECKS=0.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceAssetUploadIntegrationTest {

    /** The ONLY database this test is allowed to run against. */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /** Business-test users owned by this class; cleanup scoped to them. */
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1",
            "biz-e2e-user-2"
    );

    /** Tiny service-level upload limit so the 413 test needs no real GB file. */
    private static final long TEST_MAX_UPLOAD_BYTES = 1024L;

    private static Path storageRoot;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpikeJwtTokenService spikeJwtTokenService;

    @Autowired
    private StorageService storageService;

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        try {
            storageRoot = Files.createTempDirectory("aistudy-asset-it-");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp storage root", e);
        }
        registry.add("aistudy.storage.local.root", () -> storageRoot.toString());
        registry.add("aistudy.upload.max-file-size", () -> TEST_MAX_UPLOAD_BYTES + "B");
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

    /** FK-aware scoped cleanup: source_asset → source → learning_space. */
    private void cleanBizTestRows() {
        String placeholders = String.join(",",
                Collections.nCopies(BIZ_TEST_USERS.size(), "?"));
        Object[] users = BIZ_TEST_USERS.toArray();
        String spaceIds = "(SELECT id FROM learning_space WHERE owner_subject IN (" + placeholders + "))";
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
                    "Refusing to run SourceAssetUploadIntegrationTest: "
                            + "expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "BUSINESS-004 MUST run against " + EXPECTED_SCHEMA
                            + " only.");
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private MockMultipartFile filePart(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    private Map<String, Object> assetRowById(Long assetId) {
        return jdbcTemplate.queryForMap("SELECT * FROM source_asset WHERE id = ?", assetId);
    }

    private byte[] readStoredBytes(String storageKey) throws IOException {
        try (InputStream in = storageService.load(storageKey)) {
            return in.readAllBytes();
        }
    }

    // ==================== upload success path ====================

    /** (1) owner uploads a txt → 201 with typed body. */
    @Test
    void ownerCanUploadTextFile() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "hello raw bytes".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.sourceId").value(sourceId))
                .andExpect(jsonPath("$.assetRole").value("ORIGINAL_FILE"))
                .andExpect(jsonPath("$.originalName").value("note.txt"))
                .andExpect(jsonPath("$.mimeType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    /** (2) the source_asset row really exists in the DB. */
    @Test
    void uploadedAssetRowExistsInDatabase() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "persisted row".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long assetId = extractJsonLong(body, "id");
        Map<String, Object> row = assetRowById(assetId);
        assertEquals(spaceId, ((Number) row.get("space_id")).longValue());
        assertEquals(sourceId, ((Number) row.get("source_id")).longValue());
        assertEquals("ORIGINAL_FILE", row.get("asset_role"));
        assertEquals("note.txt", row.get("original_name"));
        assertNotNull(row.get("storage_key"), "storage_key must be persisted");
        assertTrue(((String) row.get("storage_key")).matches("\\d{4}/\\d{2}/[0-9a-fA-F-]{36}"),
                "storage_key must be server-generated yyyy/MM/<uuid>");
    }

    /** (3) StorageService.load returns bytes EXACTLY equal to the upload. */
    @Test
    void storageRoundTripPreservesExactBytes() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "raw preservation 原始字节保留".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long assetId = extractJsonLong(body, "id");
        String storageKey = (String) assetRowById(assetId).get("storage_key");
        assertArrayEquals(content, readStoredBytes(storageKey),
                "stored RAW bytes must be byte-identical to the upload");
    }

    /** (4) size_bytes in the DB is exact. */
    @Test
    void sizeBytesStoredCorrectly() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "1234567890".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sizeBytes").value(10))
                .andReturn().getResponse().getContentAsString();

        Long assetId = extractJsonLong(body, "id");
        assertEquals(10L, ((Number) assetRowById(assetId).get("size_bytes")).longValue());
    }

    /** (5) sha256 in the DB and response is the exact digest. */
    @Test
    void sha256StoredCorrectly() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "integrity check".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sha256").value(sha256Hex(content)));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_asset sa "
                        + "JOIN source s ON s.id = sa.source_id "
                        + "JOIN learning_space ls ON ls.id = sa.space_id "
                        + "WHERE ls.owner_subject = 'biz-e2e-user-1' AND sa.sha256 = ?",
                Long.class, sha256Hex(content));
        assertEquals(1L, count, "exactly one row must carry the exact sha256");
    }

    /** (6) ZIP upload → assetRole ORIGINAL_PACKAGE (RAW saved, NOT unzipped). */
    @Test
    void zipUploadGetsOriginalPackageRole() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教程包");
        byte[] zipBytes = "PK\u0003\u0004 fake zip bytes only".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("教程.zip", "application/zip", zipBytes))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetRole").value("ORIGINAL_PACKAGE"))
                .andExpect(jsonPath("$.originalName").value("教程.zip"));
    }

    /** (7) non-ZIP (pdf) → ORIGINAL_FILE. */
    @Test
    void pdfUploadGetsOriginalFileRole() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "教材");
        byte[] pdfBytes = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("book.pdf", "application/pdf", pdfBytes))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetRole").value("ORIGINAL_FILE"));
    }

    // ==================== upload rejections ====================

    /** (8) unsupported .exe → 415 and NO DB row. */
    @Test
    void unsupportedExtensionRejected415() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("virus.exe", "application/octet-stream", "MZ fake".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isUnsupportedMediaType());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_asset WHERE source_id = ?", Long.class, sourceId);
        assertEquals(0L, count, "415 must not persist any row");
    }

    /** (9) declared MIME clearly mismatching the extension → 415. */
    @Test
    void declaredMimeMismatchRejected415() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "image/png", "not an image".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isUnsupportedMediaType());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_asset WHERE source_id = ?", Long.class, sourceId);
        assertEquals(0L, count, "415 must not persist any row");
    }

    /** (10) empty file → 400. */
    @Test
    void emptyFileRejected400() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("empty.txt", "text/plain", new byte[0]))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isBadRequest());
    }

    /** (11) file over the configured service limit → 413 (limit is tiny in tests). */
    @Test
    void oversizedFileRejected413() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] big = new byte[(int) TEST_MAX_UPLOAD_BYTES + 1];

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("big.txt", "text/plain", big))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isPayloadTooLarge());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_asset WHERE source_id = ?", Long.class, sourceId);
        assertEquals(0L, count, "413 must not persist any row");
    }

    // ==================== upload authorization ====================

    /** (12) user2 upload into user1's source → 404. */
    @Test
    void otherUserCannotUploadIntoMySource() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "私有资料");

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_asset WHERE source_id = ?", Long.class, sourceId);
        assertEquals(0L, count, "404 must not persist any row");
    }

    /** (13) correct owner but sourceId belongs to ANOTHER owned space → 404. */
    @Test
    void sourceFromAnotherOwnedSpaceCannotBeUploadedInto() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long sourceInB = insertFixtureSource(spaceB, "B 的资料");

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceA, sourceInB)
                        .file(filePart("note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isNotFound());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_asset WHERE source_id = ?", Long.class, sourceInB);
        assertEquals(0L, count, "404 must not persist any row");
    }

    /** (14) anonymous POST → 401. */
    @Test
    void anonymousUploadReturns401() throws Exception {
        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/1/sources/1/assets")
                        .file(filePart("note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized());
    }

    // ==================== reads ====================

    /** (15) owner lists own source's assets → 200. */
    @Test
    void ownerCanListSourceAssets() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "list me".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("a.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("b.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].assetRole").value("ORIGINAL_FILE"));
    }

    /** (16) user2 lists user1's source assets → 404. */
    @Test
    void otherUserCannotListMySourceAssets() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "私有资料");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (17) owner gets own asset → 200. */
    @Test
    void ownerCanGetOwnAsset() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "get me".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long assetId = extractJsonLong(body, "id");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}/assets/{assetId}",
                        spaceId, sourceId, assetId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assetId))
                .andExpect(jsonPath("$.originalName").value("note.txt"));
    }

    /** (18) user2 gets user1's asset → 404. */
    @Test
    void otherUserCannotGetMyAsset() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "私有资料");
        byte[] content = "mine".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long assetId = extractJsonLong(body, "id");

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}/assets/{assetId}",
                        spaceId, sourceId, assetId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (19) correct owner/space but WRONG sourceId + valid assetId → 404. */
    @Test
    void wrongSourceIdWithValidAssetIdReturns404() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceA = insertFixtureSource(spaceId, "资料A");
        Long sourceB = insertFixtureSource(spaceId, "资料B");
        byte[] content = "under A".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceA)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long assetId = extractJsonLong(body, "id");

        // Asset lives under sourceA; path claims sourceB → 404.
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}/assets/{assetId}",
                        spaceId, sourceB, assetId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isNotFound());
    }

    /** (20) correct owner/source but WRONG spaceId + valid sourceId/assetId → 404. */
    @Test
    void wrongSpaceIdWithValidSourceAndAssetReturns404() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long sourceA = insertFixtureSource(spaceA, "资料A");
        byte[] content = "under A".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceA, sourceA)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long assetId = extractJsonLong(body, "id");

        // Path claims spaceB (sourceA belongs to spaceA) → 404.
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}/assets/{assetId}",
                        spaceB, sourceA, assetId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isNotFound());
    }

    // ==================== filename / response hygiene ====================

    /** (21) C:\fakepath\book.pdf → DB original_name = book.pdf (basename only). */
    @Test
    void fakepathFilenameStoredAsBasename() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "pdf bytes".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("C:\\fakepath\\book.pdf", "application/pdf", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalName").value("book.pdf"))
                .andReturn().getResponse().getContentAsString();

        Long assetId = extractJsonLong(body, "id");
        assertEquals("book.pdf", assetRowById(assetId).get("original_name"),
                "DB must store the basename, not the client path");
    }

    /** (22) the response must NOT expose storageKey / physical path. */
    @Test
    void responseDoesNotExposeStorageKey() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        byte[] content = "hidden key".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(filePart("note.txt", "text/plain", content))
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.originalRelativePath").doesNotExist());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist());
    }

    /** Minimal JSON long extractor for the create response. */
    private static Long extractJsonLong(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            throw new IllegalStateException("field '" + field + "' not found in response: " + json);
        }
        int colon = json.indexOf(':', keyIndex);
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (start == end) {
            throw new IllegalStateException("field '" + field + "' is not a number in response: " + json);
        }
        return Long.parseLong(json.substring(start, end));
    }
}
