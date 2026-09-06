package com.aistudy.server.ingestion;

import com.aistudy.server.spike.auth.SpikeJwtTokenService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-006 / AUTORUN-4H-PRE-RUNTIME-FIX-01 — large-document
 * ingestion integration test.
 *
 * <p>Proves the data-capacity contract fixes against real MySQL:
 * a document well above the old TEXT (~64KB) limit ingests fully —
 * {@code source_page.extracted_text} (LONGTEXT) persists the WHOLE
 * document, and every {@code content_block.normalized_text} (TEXT)
 * stays within the UTF-8 byte budget with zero content loss.
 *
 * <p>This class deliberately does NOT override
 * {@code aistudy.ingestion.text.max-document-bytes} (production
 * default 64MB applies) and does NOT touch the 1KB-limit class —
 * {@link ContentIngestionIntegrationTest} keeps its
 * DOCUMENT_TOO_LARGE coverage.
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentLargeDocumentIntegrationTest {

    /** The ONLY database this test is allowed to run against. */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /** Business-test users owned by this class; cleanup scoped to them. */
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1",
            "biz-e2e-user-2"
    );

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
    private SpikeJwtTokenService spikeJwtTokenService;

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        try {
            storageRoot = Files.createTempDirectory("aistudy-large-it-");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp storage root", e);
        }
        // text ingestion limit intentionally NOT overridden: production
        // default (64MB) applies — the fixtures (~100KB) are far above
        // the old TEXT capacity but far below the limit.
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

    /**
     * Uploads + ingests a text asset into the GIVEN space; returns the
     * ingested block texts.
     *
     * <p>Scoping consistency is asserted explicitly (RUNTIME-FIX-02-D):
     * the fixture source must really belong to the path space and the
     * token subject must own that space; the created asset must carry
     * exactly the path spaceId/sourceId. The 404 anti-IDOR contract is
     * thereby exercised in reverse — fixtures are proven consistent
     * with the paths they are sent to.
     */
    private List<String> ingestAndGetBlocks(Long spaceId, Long sourceId,
                                            String fileName, String content) throws Exception {
        String token = tokenFor("biz-e2e-user-1");

        // fixture/path consistency: source belongs to space; space owned by token subject
        Map<String, Object> sourceRow = jdbcTemplate.queryForMap(
                "SELECT s.space_id AS space_id, ls.owner_subject AS owner_subject "
                        + "FROM source s JOIN learning_space ls ON ls.id = s.space_id WHERE s.id = ?",
                sourceId);
        assertEquals(spaceId, ((Number) sourceRow.get("space_id")).longValue(),
                "fixture source must belong to the path space");
        assertEquals("biz-e2e-user-1", sourceRow.get("owner_subject"),
                "path space must be owned by the token subject");

        MvcResult upload = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(new MockMultipartFile("file", fileName, "text/plain",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long assetId = extractJsonLong(upload.getResponse().getContentAsString(), "id");

        // asset really belongs to the path space + source (owner-scoped write path)
        Map<String, Object> assetRow = jdbcTemplate.queryForMap(
                "SELECT space_id, source_id FROM source_asset WHERE id = ?", assetId);
        assertEquals(spaceId, ((Number) assetRow.get("space_id")).longValue(),
                "asset must belong to the path space");
        assertEquals(sourceId, ((Number) assetRow.get("source_id")).longValue(),
                "asset must belong to the path source");

        mockMvc.perform(post(JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        Map<String, Object> page = jdbcTemplate.queryForMap(
                "SELECT extracted_text FROM source_page WHERE space_id = ? AND source_id = ?",
                spaceId, sourceId);
        assertEquals(content, page.get("extracted_text"),
                "source_page.extracted_text (LONGTEXT) must persist the FULL document");

        return jdbcTemplate.queryForList(
                "SELECT normalized_text FROM content_block "
                        + "WHERE space_id = ? AND source_id = ? ORDER BY sort_order",
                String.class, spaceId, sourceId);
    }

    /** FK-aware scoped cleanup, deepest first. */
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
                    "Refusing to run ContentLargeDocumentIntegrationTest: "
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

    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ==================== tests ====================

    /** (1) ~101KB ASCII document: LONGTEXT page + 2 bounded TEXT blocks, lossless. */
    @Test
    void largeAsciiDocumentPersistsFully() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "大文本");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("a".repeat(100)); // 1000 lines x 100 ASCII = ~101KB
        }
        String content = sb.toString();
        assertTrue(utf8Bytes(content) > 100_000, "fixture must exceed 100KB");

        List<String> blocks = ingestAndGetBlocks(spaceId, sourceId, "big.txt", content);

        assertEquals(2, blocks.size(), "101KB must split into 2 blocks at line boundaries");
        for (String block : blocks) {
            assertTrue(utf8Bytes(block) <= 60_000,
                    "block exceeds the 60KB UTF-8 budget: " + utf8Bytes(block));
        }
        assertEquals(content, String.join("\n", blocks),
                "joining the blocks must reproduce the document exactly");
    }

    /** (2) Chinese document (~75KB UTF-8): byte-bounded blocks, lossless. */
    @Test
    void largeChineseDocumentPersistsFully() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "中文大文本");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("中".repeat(100)); // 250 lines x 100 hanzi = 75,000 bytes
        }
        String content = sb.toString();
        assertTrue(utf8Bytes(content) > 75_000, "fixture must exceed 75KB");

        List<String> blocks = ingestAndGetBlocks(spaceId, sourceId, "zh.txt", content);

        assertTrue(blocks.size() >= 2, "75KB of Chinese must split into 2+ blocks");
        for (String block : blocks) {
            assertTrue(utf8Bytes(block) <= 60_000,
                    "block exceeds the 60KB UTF-8 budget: " + utf8Bytes(block));
        }
        assertEquals(content, String.join("\n", blocks),
                "joining the blocks must reproduce the document exactly");
    }

    /** (3) one single overlong line (~100KB, no newline): in-line split, lossless. */
    @Test
    void singleOverlongLinePersistsFully() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "单行大文本");
        String content = "x".repeat(100_000);
        assertTrue(utf8Bytes(content) > 60_000, "fixture must exceed one block");

        List<String> blocks = ingestAndGetBlocks(spaceId, sourceId, "line.txt", content);

        assertEquals(2, blocks.size(), "100KB single line must split into 2 blocks");
        for (String block : blocks) {
            assertTrue(utf8Bytes(block) <= 60_000,
                    "block exceeds the 60KB UTF-8 budget: " + utf8Bytes(block));
        }
        // chunks of one line concatenate WITHOUT any separator
        assertEquals(content, String.join("", blocks),
                "concatenating the chunks must reproduce the line exactly");
    }

    /** (4) emoji line (~80KB UTF-8): surrogate pairs never cut, lossless roundtrip. */
    @Test
    void emojiLinePersistsWithoutBrokenSurrogates() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "Emoji 大文本");
        String emoji = "\uD83D\uDE00"; // U+1F600, 4 UTF-8 bytes
        String content = emoji.repeat(20_000); // 80,000 bytes
        assertTrue(utf8Bytes(content) > 60_000, "fixture must exceed one block");

        List<String> blocks = ingestAndGetBlocks(spaceId, sourceId, "emoji.txt", content);

        assertEquals(2, blocks.size(), "80KB emoji line must split into 2 blocks");
        for (String block : blocks) {
            assertTrue(utf8Bytes(block) <= 60_000,
                    "block exceeds the 60KB UTF-8 budget: " + utf8Bytes(block));
            assertEquals(block, new String(block.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                    "block must round-trip UTF-8 (no cut surrogate pair)");
        }
        assertEquals(content, String.join("", blocks),
                "concatenating the chunks must reproduce the line exactly");
    }
}
