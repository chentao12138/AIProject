package com.aistudy.server.source.outline;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.source.outline.entity.SourceOutlineNode;
import com.aistudy.server.source.outline.service.SourceOutlineNodeService;
import com.aistudy.server.source.outline.service.SourceOutlineNodeService.ProposedHeading;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Audit F-07 — the proposed outline tree a reviewer signs off on.
 *
 * <p>Manual outline CRUD existed, the "machine proposes, human reviews" half did
 * not. Real MySQL because the guarantees that matter here are storage-level: a
 * re-run replaces only proposals ({@code status = 'NEEDS_REVIEW'}) and never
 * touches a node a human confirmed, and the parent chain is built from heading
 * levels rather than from whatever id order the rows happen to have.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceOutlineProposalIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String OWNER = "outline-it-user-1";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SourceOutlineNodeService sourceOutlineNodeService;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    private Long spaceId;
    private Long sourceId;
    private Long revisionId;

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
        OwnedSpaceReset.forSubjects(jdbcTemplate, List.of(OWNER));
        spaceId = insert("learning_space", "INSERT INTO learning_space "
                + "(name, owner_subject, status, created_at, updated_at) VALUES ('目录建议空间', '"
                + OWNER + "', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        sourceId = insert("source", "INSERT INTO source "
                + "(space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                + "VALUES (" + spaceId + ", '教程', 'UPLOAD', 'NEEDS_REVIEW', '" + OWNER
                + "', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        revisionId = insert("extraction_revision", "INSERT INTO extraction_revision "
                + "(space_id, source_id, version, status, created_at) VALUES (" + spaceId + ", "
                + sourceId + ", 1, 'NEEDS_REVIEW', CURRENT_TIMESTAMP(6))");
    }

    @AfterEach
    void cleanUp() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, List.of(OWNER));
    }

    // ==================== tests ====================

    /** Levels 1/2/3 become CHAPTER → SECTION → SUBSECTION with a real parent chain. */
    @Test
    void proposalBuildsAReviewedHierarchyAndParsesNumberLabels() {
        int written = sourceOutlineNodeService.replaceProposedTree(OWNER, spaceId, sourceId,
                revisionId, List.of(
                        new ProposedHeading("第1章 绪论", 1),
                        new ProposedHeading("1.1 数据库系统概述", 2),
                        new ProposedHeading("1.1.1 基本概念", 3)));

        assertEquals(3, written);
        List<SourceOutlineNode> nodes = sourceOutlineNodeService.listByRevision(spaceId, revisionId);
        assertEquals(3, nodes.size());

        SourceOutlineNode chapter = nodes.get(0);
        assertEquals("CHAPTER", chapter.getNodeType());
        assertEquals("第1章", chapter.getNumberLabel());
        assertNull(chapter.getParentId(), "a level-1 heading has no parent");
        assertEquals("NEEDS_REVIEW", chapter.getStatus());
        assertEquals(revisionId, chapter.getExtractionRevisionId());

        SourceOutlineNode section = nodes.get(1);
        assertEquals("SECTION", section.getNodeType());
        assertEquals("1.1", section.getNumberLabel());
        assertEquals(chapter.getId(), section.getParentId());

        SourceOutlineNode subsection = nodes.get(2);
        assertEquals("SUBSECTION", subsection.getNodeType());
        assertEquals(section.getId(), subsection.getParentId());
    }

    /**
     * The proposal is rerunnable: a second run replaces its own rows instead of
     * stacking a second copy of the tree — which is what makes "regenerate the
     * suggestion" safe for the reviewer.
     */
    @Test
    void rerunningAProposalReplacesItWithoutDuplicates() {
        sourceOutlineNodeService.replaceProposedTree(OWNER, spaceId, sourceId, revisionId,
                List.of(new ProposedHeading("第1章 绪论", 1), new ProposedHeading("第2章 实体", 1)));
        int second = sourceOutlineNodeService.replaceProposedTree(OWNER, spaceId, sourceId,
                revisionId, List.of(new ProposedHeading("第1章 绪论", 1)));

        assertEquals(1, second);
        assertEquals(List.of("第1章 绪论"), jdbcTemplate.queryForList(
                "SELECT title FROM source_outline_node WHERE extraction_revision_id = ?",
                String.class, revisionId));
    }

    /**
     * A node the reviewer already confirmed is not machine-owned any more, so a
     * new proposal must leave it alone even though it sits in the same revision.
     */
    @Test
    void confirmedNodesSurviveAReproposal() {
        Long confirmed = insert("source_outline_node", "INSERT INTO source_outline_node "
                + "(space_id, source_id, extraction_revision_id, node_type, title, sort_order, "
                + "status, created_at, updated_at) VALUES (" + spaceId + ", " + sourceId + ", "
                + revisionId + ", 'CHAPTER', '人工确认的章', 0, 'CONFIRMED', "
                + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");

        sourceOutlineNodeService.replaceProposedTree(OWNER, spaceId, sourceId, revisionId,
                List.of(new ProposedHeading("第1章 绪论", 1)));

        List<String> titles = jdbcTemplate.queryForList(
                "SELECT title FROM source_outline_node WHERE extraction_revision_id = ? "
                        + "ORDER BY id", String.class, revisionId);
        assertEquals(List.of("人工确认的章", "第1章 绪论"), titles);
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT status FROM source_outline_node WHERE id = ?", String.class, confirmed));
    }

    /** Nothing usable in, nothing written; an unknown space or source is a no-op. */
    @Test
    void emptyAndForeignInputsWriteNothing() {
        assertEquals(0, sourceOutlineNodeService.replaceProposedTree(
                OWNER, spaceId, sourceId, revisionId, List.of()));
        assertEquals(0, sourceOutlineNodeService.replaceProposedTree(
                OWNER, spaceId, sourceId, null, List.of(new ProposedHeading("第1章", 1))));
        assertEquals(0, sourceOutlineNodeService.replaceProposedTree(
                "outline-it-user-1", 987654L, sourceId, revisionId,
                List.of(new ProposedHeading("第1章", 1))));

        assertEquals(0, countNodes());
    }

    /**
     * Levels are clamped into the three the outline vocabulary supports (a
     * heading deeper than that becomes a SUBSECTION rather than a fourth kind),
     * blank titles are skipped, and a title with no leading number gets no label.
     */
    @Test
    void levelsAreClampedAndBlankTitlesSkipped() {
        int written = sourceOutlineNodeService.replaceProposedTree(OWNER, spaceId, sourceId,
                revisionId, List.of(
                        new ProposedHeading("第七章 关系", 9),
                        new ProposedHeading("   ", 1),
                        new ProposedHeading("附录", -2)));

        assertEquals(2, written);
        List<SourceOutlineNode> nodes = sourceOutlineNodeService.listByRevision(spaceId, revisionId);
        assertEquals("SUBSECTION", nodes.get(0).getNodeType(), "level 9 clamps to the deepest kind");
        assertEquals("第七章", nodes.get(0).getNumberLabel());
        assertEquals("CHAPTER", nodes.get(1).getNodeType(), "a negative level clamps to the top");
        assertNull(nodes.get(1).getNumberLabel(), "'附录' carries no leading number");
        assertEquals(2, nodes.size(), "the blank title must not have been stored");
    }

    /** The proposal is bounded: a mis-parsed document cannot flood the queue. */
    @Test
    void proposalIsCapped() {
        java.util.List<ProposedHeading> headings = new java.util.ArrayList<>();
        for (int i = 1; i <= SourceOutlineNodeService.MAX_PROPOSED_NODES + 5; i++) {
            headings.add(new ProposedHeading("第" + i + "章", 1));
        }

        assertEquals(SourceOutlineNodeService.MAX_PROPOSED_NODES,
                sourceOutlineNodeService.replaceProposedTree(
                        OWNER, spaceId, sourceId, revisionId, headings));
        assertEquals(SourceOutlineNodeService.MAX_PROPOSED_NODES, countNodes());
    }

    // ==================== helpers ====================

    private long countNodes() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_outline_node WHERE space_id = ?",
                Integer.class, spaceId).longValue();
    }

    private Long insert(String table, String sql) {
        jdbcTemplate.update(sql);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " ORDER BY id DESC LIMIT 1", Long.class);
        assertNotNull(id);
        return id;
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run SourceOutlineProposalIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
