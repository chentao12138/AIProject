package com.aistudy.server.knowledge;

import com.aistudy.server.auth.service.JwtAccessTokenService;
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
 * BUSINESS-007 — KnowledgePointSource provenance vertical slice
 * integration test.
 *
 * <p>Real MySQL ({@code flyway-it} profile, schema guard against
 * {@code aistudy_flyway_test}), MockMvc + real JWT, and a TEMPORARY
 * storage root. The full real chain is exercised: BUSINESS-004 upload
 * → BUSINESS-006 text ingestion (produces ContentBlocks) →
 * BUSINESS-003 knowledge point creation → BUSINESS-007 link/list.
 *
 * <h3>Coverage</h3>
 *
 * <p>Same-space linking accepted (incl. blocks from two sources of
 * the same space); idempotent re-link; batch all-or-nothing (one bad
 * id → 404, zero rows); cross-space block/point → 404; non-owner
 * point/block → 404; soft-deleted point → 404; empty batch → 400;
 * list owner + 404 matrix; optional fields NULL; anonymous → 401.
 *
 * <h3>Database safety</h3>
 *
 * <ul>
 *   <li>Schema guard: {@code SELECT DATABASE()} must equal
 *       {@code aistudy_flyway_test} exactly.</li>
 *   <li>FK-aware cleanup order: knowledge_point_source →
 *       knowledge_point → knowledge_category → content_block →
 *       source_page → ingestion_job → source_asset → source →
 *       learning_space (all scoped to biz-e2e users).</li>
 *   <li>No unqualified DELETE / TRUNCATE / DROP; no
 *       FOREIGN_KEY_CHECKS=0.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgePointProvenanceIntegrationTest {

    /** The ONLY database this test is allowed to run against. */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /** Business-test users owned by this class; cleanup scoped to them. */
    private static final List<String> BIZ_TEST_USERS = List.of(
            "biz-e2e-user-1",
            "biz-e2e-user-2"
    );

    private static final String LINK_BASE =
            "/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/sources";
    private static final String POINTS = "/api/v1/spaces/{spaceId}/knowledge-points";
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
            storageRoot = Files.createTempDirectory("aistudy-provenance-it-");
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

    /** Uploads a text asset and ingests it; returns the source's block ids. */
    private List<Long> ingestTextSource(String token, Long spaceId, Long sourceId,
                                        String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MvcResult upload = mockMvc.perform(multipart(HttpMethod.POST,
                        "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets",
                        spaceId, sourceId)
                        .file(new MockMultipartFile("file", fileName, "text/plain", bytes))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        Long assetId = extractJsonLong(upload.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(JOBS, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        return jdbcTemplate.queryForList(
                "SELECT id FROM content_block WHERE space_id = ? AND source_id = ? ORDER BY sort_order",
                Long.class, spaceId, sourceId);
    }

    /** Creates a USER_CURATED DRAFT point through the BUSINESS-003 API. */
    private Long createPoint(String token, Long spaceId, String title) throws Exception {
        MvcResult result = mockMvc.perform(post(POINTS, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"point body\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return extractJsonLong(result.getResponse().getContentAsString(), "id");
    }

    private String linkBody(String token, Long spaceId, Long kpId, List<Long> blockIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (Long id : blockIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        return mockMvc.perform(post(LINK_BASE, spaceId, kpId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[" + ids + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /** FK-aware scoped cleanup, deepest first. */
    private void cleanBizTestRows() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, BIZ_TEST_USERS);
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run KnowledgePointProvenanceIntegrationTest: "
                            + "expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "BUSINESS-007 MUST run against " + EXPECTED_SCHEMA
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

    private long countLinks(Long spaceId, Long kpId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_point_source "
                        + "WHERE space_id = ? AND knowledge_point_id = ?",
                Long.class, spaceId, kpId);
    }

    // ==================== same-space linking ====================

    /** (1) owner links blocks of the same space → 201 + exact DB rows. */
    @Test
    void ownerLinksBlocksToPoint() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        List<Long> blockIds = ingestTextSource(token, spaceId, sourceId,
                "note.txt", "line one\nline two\n\npara two\n");
        assertEquals(2, blockIds.size(), "fixture must produce 2 blocks");
        Long kpId = createPoint(token, spaceId, "知识点一");

        String body = linkBody(token, spaceId, kpId, blockIds);

        assertTrue(body.contains("\"knowledgePointId\":" + kpId), body);
        assertTrue(body.contains("\"contentBlockId\":" + blockIds.get(0)), body);
        assertTrue(body.contains("\"contentBlockId\":" + blockIds.get(1)), body);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM knowledge_point_source "
                        + "WHERE space_id = ? AND knowledge_point_id = ? ORDER BY id",
                spaceId, kpId);
        assertEquals(2, rows.size());
        assertEquals(spaceId, ((Number) rows.get(0).get("space_id")).longValue());
        assertEquals(kpId, ((Number) rows.get(0).get("knowledge_point_id")).longValue());
        assertEquals(blockIds.get(0), ((Number) rows.get(0).get("content_block_id")).longValue());
        assertEquals(blockIds.get(1), ((Number) rows.get(1).get("content_block_id")).longValue());
        assertNull(rows.get(0).get("relation_type"), "relation_type stays NULL in V1");
        assertNull(rows.get(0).get("relevance_score"), "relevance_score stays NULL in V1");
        assertEquals("biz-e2e-user-1", rows.get(0).get("created_by_user_id"));
        assertNotNull(rows.get(0).get("created_at"));
    }

    /** (2) blocks of TWO sources of the SAME space can both be linked. */
    @Test
    void blocksFromTwoSourcesOfSameSpaceCanBeLinked() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceA = insertFixtureSource(spaceId, "资料A");
        Long sourceB = insertFixtureSource(spaceId, "资料B");
        String token = tokenFor("biz-e2e-user-1");
        List<Long> blocksA = ingestTextSource(token, spaceId, sourceA, "a.txt", "aa");
        List<Long> blocksB = ingestTextSource(token, spaceId, sourceB, "b.txt", "bb");
        Long kpId = createPoint(token, spaceId, "跨资料知识点");

        String body = linkBody(token, spaceId, kpId, List.of(blocksA.get(0), blocksB.get(0)));

        assertTrue(body.contains("\"contentBlockId\":" + blocksA.get(0)), body);
        assertTrue(body.contains("\"contentBlockId\":" + blocksB.get(0)), body);
        assertEquals(2L, countLinks(spaceId, kpId));
    }

    /** (3) re-linking an existing pair is a no-op (idempotent add). */
    @Test
    void relinkingExistingPairIsNoOp() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        List<Long> blockIds = ingestTextSource(token, spaceId, sourceId, "note.txt", "one\ntwo\n\nthree");
        Long kpId = createPoint(token, spaceId, "知识点一");

        linkBody(token, spaceId, kpId, blockIds);
        String second = linkBody(token, spaceId, kpId, blockIds);

        assertEquals(2L, countLinks(spaceId, kpId),
                "re-linking existing pairs must not duplicate rows");
        assertTrue(second.contains("\"contentBlockId\":" + blockIds.get(0)), second);
    }

    // ==================== same-space invariant enforcement ====================

    /** (4) a batch with ONE invalid block id rejects the WHOLE request (no partial). */
    @Test
    void batchWithOneInvalidBlockRejectsAll() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        List<Long> blockIds = ingestTextSource(token, spaceId, sourceId, "note.txt", "x");
        Long kpId = createPoint(token, spaceId, "知识点一");

        mockMvc.perform(post(LINK_BASE, spaceId, kpId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[" + blockIds.get(0) + ",999999]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        assertEquals(0L, countLinks(spaceId, kpId),
                "one invalid id must reject the whole batch — zero rows");
    }

    /** (5) a block from ANOTHER space cannot be linked (cross-space → 404). */
    @Test
    void crossSpaceBlockRejected() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long sourceB = insertFixtureSource(spaceB, "B 的资料");
        String token = tokenFor("biz-e2e-user-1");
        List<Long> blocksB = ingestTextSource(token, spaceB, sourceB, "b.txt", "bb");
        Long kpInA = createPoint(token, spaceA, "A 的知识点");

        mockMvc.perform(post(LINK_BASE, spaceA, kpInA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[" + blocksB.get(0) + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        assertEquals(0L, countLinks(spaceA, kpInA));
    }

    /** (6) a point from ANOTHER space cannot be linked via this space path. */
    @Test
    void crossSpacePointRejected() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        Long sourceA = insertFixtureSource(spaceA, "A 的资料");
        String token = tokenFor("biz-e2e-user-1");
        List<Long> blocksA = ingestTextSource(token, spaceA, sourceA, "a.txt", "aa");
        Long kpInB = createPoint(token, spaceB, "B 的知识点");

        mockMvc.perform(post(LINK_BASE, spaceA, kpInB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[" + blocksA.get(0) + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (7) another user's point → 404. */
    @Test
    void otherUsersPointRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token1 = tokenFor("biz-e2e-user-1");
        List<Long> blockIds = ingestTextSource(token1, spaceId, sourceId, "note.txt", "x");
        Long kpId = createPoint(token1, spaceId, "私有知识点");

        mockMvc.perform(post(LINK_BASE, spaceId, kpId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[" + blockIds.get(0) + "]}")
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (8) another user's block → 404. */
    @Test
    void otherUsersBlockRejected() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long sourceA = insertFixtureSource(spaceA, "A 的资料");
        String token1 = tokenFor("biz-e2e-user-1");
        Long kpId = createPoint(token1, spaceA, "A 的知识点");
        Long spaceB = insertFixtureSpace("biz-e2e-user-2", "user2 空间B");
        Long sourceB = insertFixtureSource(spaceB, "B 的资料");
        String token2 = tokenFor("biz-e2e-user-2");
        List<Long> blocksB = ingestTextSource(token2, spaceB, sourceB, "b.txt", "bb");

        mockMvc.perform(post(LINK_BASE, spaceA, kpId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[" + blocksB.get(0) + "]}")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isNotFound());
    }

    /** (9) nonexistent point → 404. */
    @Test
    void nonexistentPointRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        List<Long> blockIds = ingestTextSource(token, spaceId, sourceId, "note.txt", "x");

        mockMvc.perform(post(LINK_BASE, spaceId, 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[" + blockIds.get(0) + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (10) soft-deleted point → 404 (deleted_at IS NULL rule). */
    @Test
    void softDeletedPointRejected() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        List<Long> blockIds = ingestTextSource(token, spaceId, sourceId, "note.txt", "x");
        Long kpId = createPoint(token, spaceId, "待删知识点");
        jdbcTemplate.update(
                "UPDATE knowledge_point SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", kpId);

        mockMvc.perform(post(LINK_BASE, spaceId, kpId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[" + blockIds.get(0) + "]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (11) empty contentBlockIds → 400. */
    @Test
    void emptyContentBlockIdsReturns400() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token = tokenFor("biz-e2e-user-1");
        Long kpId = createPoint(token, spaceId, "知识点");

        mockMvc.perform(post(LINK_BASE, spaceId, kpId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[]}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** (12) anonymous link → 401. */
    @Test
    void anonymousLinkReturns401() throws Exception {
        mockMvc.perform(post(LINK_BASE, 1L, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentBlockIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== list ====================

    /** (13) owner lists the point's provenance links. */
    @Test
    void ownerListsProvenanceLinks() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        Long sourceId = insertFixtureSource(spaceId, "笔记");
        String token = tokenFor("biz-e2e-user-1");
        // "one\n\ntwo": blank line separates TWO paragraphs -> TWO
        // ContentBlocks (RUNTIME-FIX-02-G). Assert the fixture really
        // produced 2 blocks before linking.
        List<Long> blockIds = ingestTextSource(token, spaceId, sourceId, "note.txt", "one\n\ntwo");
        assertEquals(2, blockIds.size(), "fixture must produce exactly 2 content blocks");
        Long kpId = createPoint(token, spaceId, "知识点一");
        linkBody(token, spaceId, kpId, blockIds);

        mockMvc.perform(get(LINK_BASE, spaceId, kpId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].knowledgePointId").value(kpId))
                .andExpect(jsonPath("$[0].contentBlockId").value(blockIds.get(0)))
                .andExpect(jsonPath("$[1].contentBlockId").value(blockIds.get(1)));
    }

    /** (14) other user cannot list my links → 404. */
    @Test
    void otherUserCannotListMyLinks() throws Exception {
        Long spaceId = insertFixtureSpace("biz-e2e-user-1", "user1 空间");
        String token1 = tokenFor("biz-e2e-user-1");
        Long kpId = createPoint(token1, spaceId, "知识点");

        mockMvc.perform(get(LINK_BASE, spaceId, kpId)
                        .header("Authorization", "Bearer " + tokenFor("biz-e2e-user-2")))
                .andExpect(status().isNotFound());
    }

    /** (15) a point of another space cannot be listed via this space path. */
    @Test
    void crossSpacePointCannotBeListed() throws Exception {
        Long spaceA = insertFixtureSpace("biz-e2e-user-1", "user1 空间A");
        Long spaceB = insertFixtureSpace("biz-e2e-user-1", "user1 空间B");
        String token = tokenFor("biz-e2e-user-1");
        Long kpInB = createPoint(token, spaceB, "B 的知识点");

        mockMvc.perform(get(LINK_BASE, spaceA, kpInB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
