package com.aistudy.server.ingestion;

import com.aistudy.server.auth.service.JwtAccessTokenService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit F-11 — the version compare has to be a real revision-to-revision diff.
 *
 * <p>Pages, blocks and outline nodes are re-created per extraction revision, so
 * the two sides never share a primary key. The diff keys on document identity
 * (page number, page plus position, title path); keying on the row id made every
 * re-extraction read as "all pages removed, all pages added", kept the outline
 * counts at a hardcoded zero, and left {@code updatedContentBlocks} an empty map.
 *
 * <p>Real MySQL ({@code aistudy_flyway_test}), MockMvc + real JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceVersionCompareIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> SUBJECTS = List.of("ver-it-user-1");
    private static final String COMPARE =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/versions/compare";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    private Long spaceId;
    private Long sourceId;
    private Long revisionOne;
    private Long revisionTwo;

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
        OwnedSpaceReset.forSubjects(jdbcTemplate, SUBJECTS);
        fixture();
    }

    @AfterEach
    void cleanUp() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, SUBJECTS);
    }

    // ==================== tests ====================

    /**
     * Two revisions of the same document: the shared pages are recognised by
     * page number (not by row id), so only the genuinely new page counts as added
     * and nothing counts as removed. Before F-11 this returned added=2, removed=2.
     */
    @Test
    void pagesMatchByPageNumberAcrossRevisions() throws Exception {
        mockMvc.perform(post(COMPARE, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromRevisionId\":" + revisionOne
                                + ",\"toRevisionId\":" + revisionTwo + "}")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPagesBefore").value(2))
                .andExpect(jsonPath("$.totalPagesAfter").value(3))
                .andExpect(jsonPath("$.pagesAdded").value(1))
                .andExpect(jsonPath("$.pagesRemoved").value(0))
                .andExpect(jsonPath("$.pagesChanged").value(1))
                .andExpect(jsonPath("$.pagesReorderedIds.length()").value(1));
    }

    /**
     * A block whose text or type changed reads as ONE changed block carrying its
     * new text. The old key hashed the text into the identity, so an edit looked
     * like a removal plus an addition and {@code updatedContentBlocks} stayed empty.
     */
    @Test
    void blockEditsAreReportedWithTheirNewText() throws Exception {
        mockMvc.perform(post(COMPARE, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromRevisionId\":" + revisionOne
                                + ",\"toRevisionId\":" + revisionTwo + "}")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocksChanged").value(2))
                .andExpect(jsonPath("$.blocksAdded").value(1))
                .andExpect(jsonPath("$.blocksRemoved").value(1))
                .andExpect(jsonPath("$.updatedContentBlocks.length()").value(2));
    }

    /** Outline counts were hardcoded 0; a moved page span is a change, not a rename. */
    @Test
    void outlineTreeDiffIsCounted() throws Exception {
        mockMvc.perform(post(COMPARE, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromRevisionId\":" + revisionOne
                                + ",\"toRevisionId\":" + revisionTwo + "}")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outlineAdded").value(1))
                .andExpect(jsonPath("$.outlineRemoved").value(1))
                .andExpect(jsonPath("$.outlineChanged").value(1));
    }

    /** Comparing a revision with itself is an empty diff on every dimension. */
    @Test
    void comparingARevisionWithItselfIsEmpty() throws Exception {
        mockMvc.perform(post(COMPARE, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromRevisionId\":" + revisionOne
                                + ",\"toRevisionId\":" + revisionOne + "}")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagesAdded").value(0))
                .andExpect(jsonPath("$.pagesRemoved").value(0))
                .andExpect(jsonPath("$.pagesChanged").value(0))
                .andExpect(jsonPath("$.blocksAdded").value(0))
                .andExpect(jsonPath("$.blocksRemoved").value(0))
                .andExpect(jsonPath("$.blocksChanged").value(0))
                .andExpect(jsonPath("$.outlineAdded").value(0))
                .andExpect(jsonPath("$.outlineRemoved").value(0))
                .andExpect(jsonPath("$.outlineChanged").value(0))
                .andExpect(jsonPath("$.updatedContentBlocks.length()").value(0));
    }

    /** An unknown revision is a 404, and another space's source is invisible. */
    @Test
    void unknownRevisionAndForeignSourceAreNotFound() throws Exception {
        mockMvc.perform(post(COMPARE, spaceId, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromRevisionId\":" + revisionOne + ",\"toRevisionId\":999999}")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());

        Long otherSpace = insertSpace("ver-it-user-1", "另一个空间");
        mockMvc.perform(post(COMPARE, otherSpace, sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromRevisionId\":" + revisionOne
                                + ",\"toRevisionId\":" + revisionTwo + "}")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    // ==================== fixtures ====================

    private String token() {
        String token = jwtAccessTokenService.issueAccessToken("ver-it-user-1");
        assertNotNull(token, "token must not be null");
        return token;
    }

    /**
     * Revision 1 → revision 2 changes exactly one page's text, reorders page 2,
     * adds page 3, edits two blocks (one by text, one by type), drops one block,
     * adds one block, moves a chapter's page span, drops one section and adds one.
     */
    private void fixture() {
        spaceId = insertSpace("ver-it-user-1", "版本对比空间");
        sourceId = insertSource(spaceId, "ver-it-user-1");
        revisionOne = insertRevision(sourceId, spaceId, 1);
        revisionTwo = insertRevision(sourceId, spaceId, 2);

        Long onePage1 = insertPage(spaceId, sourceId, revisionOne, 1, 1, "第一章 原文");
        Long onePage2 = insertPage(spaceId, sourceId, revisionOne, 2, 2, "第二章 原文");
        insertBlock(spaceId, sourceId, revisionOne, onePage1, 0, "PARAGRAPH", "封面文字");
        insertBlock(spaceId, sourceId, revisionOne, onePage1, 1, "PARAGRAPH", "第一章 原文正文");
        insertBlock(spaceId, sourceId, revisionOne, onePage2, 0, "PARAGRAPH", "第二章 将被删除的段落");
        Long chapterOne = insertOutline(spaceId, sourceId, revisionOne, null, "CHAPTER",
                "第1章 绪论", 1, 3, 0);
        insertOutline(spaceId, sourceId, revisionOne, chapterOne, "SECTION",
                "1.1 背景", 1, 1, 0);

        Long twoPage1 = insertPage(spaceId, sourceId, revisionTwo, 1, 1, "第一章 改过的原文");
        insertPage(spaceId, sourceId, revisionTwo, 2, 3, "第二章 原文");
        Long twoPage3 = insertPage(spaceId, sourceId, revisionTwo, 3, 2, "第三章 新增");
        insertBlock(spaceId, sourceId, revisionTwo, twoPage1, 0, "HEADING", "封面文字");
        insertBlock(spaceId, sourceId, revisionTwo, twoPage1, 1, "PARAGRAPH", "第一章 新正文");
        insertBlock(spaceId, sourceId, revisionTwo, twoPage3, 0, "PARAGRAPH", "第三章 新增段落");
        Long chapterTwo = insertOutline(spaceId, sourceId, revisionTwo, null, "CHAPTER",
                "第1章 绪论", 1, 4, 0);
        insertOutline(spaceId, sourceId, revisionTwo, chapterTwo, "SECTION",
                "1.2 结构", 2, 2, 1);

        jdbcTemplate.update("UPDATE source SET current_extraction_revision_id = ? WHERE id = ?",
                revisionTwo, sourceId);
    }

    private Long insertSpace(String owner, String name) {
        jdbcTemplate.update("INSERT INTO learning_space "
                        + "(name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, owner);
        return lastId("learning_space");
    }

    private Long insertSource(Long space, String owner) {
        jdbcTemplate.update("INSERT INTO source "
                        + "(space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, '教材', 'UPLOAD', 'ACTIVE', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                space, owner);
        return lastId("source");
    }

    private Long insertRevision(Long source, Long space, int version) {
        jdbcTemplate.update("INSERT INTO extraction_revision "
                        + "(space_id, source_id, version, status, created_at) "
                        + "VALUES (?, ?, ?, 'NEEDS_REVIEW', CURRENT_TIMESTAMP(6))",
                space, source, version);
        return lastId("extraction_revision");
    }

    private Long insertPage(Long space, Long source, Long revision, int pageNumber,
                            int pageOrder, String text) {
        jdbcTemplate.update("INSERT INTO source_page "
                        + "(space_id, source_id, extraction_revision_id, source_page_number, page_order, "
                        + " page_type, order_status, extracted_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'BODY', 'AUTO', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                space, source, revision, pageNumber, pageOrder, text);
        return lastId("source_page");
    }

    private Long insertBlock(Long space, Long source, Long revision, Long page, int sortOrder,
                             String blockType, String text) {
        jdbcTemplate.update("INSERT INTO content_block "
                        + "(space_id, source_id, source_page_id, extraction_revision_id, block_type, "
                        + " sort_order, normalized_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                space, source, page, revision, blockType, sortOrder, text);
        return lastId("content_block");
    }

    private Long insertOutline(Long space, Long source, Long revision, Long parentId,
                               String nodeType, String title, Integer startPage, Integer endPage,
                               int sortOrder) {
        jdbcTemplate.update("INSERT INTO source_outline_node "
                        + "(space_id, source_id, parent_id, extraction_revision_id, node_type, title, "
                        + " sort_order, start_page, end_page, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                space, source, parentId, revision, nodeType, title, sortOrder, startPage, endPage);
        return lastId("source_outline_node");
    }

    private Long lastId(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " ORDER BY id DESC LIMIT 1", Long.class);
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run SourceVersionCompareIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
