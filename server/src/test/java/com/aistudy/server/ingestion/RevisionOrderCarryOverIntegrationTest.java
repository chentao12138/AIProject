package com.aistudy.server.ingestion;

import com.aistudy.server.ingestion.job.service.IngestionRevisionPublisher;
import com.aistudy.server.source.page.entity.SourcePage;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit F-31, the half that was missing: a human-confirmed page order has to
 * survive a re-extraction.
 *
 * <p>The reorder endpoint already renumbered the whole visible set and marked the
 * human's placements {@code CONFIRMED}, but pages belong to an extraction
 * revision, so re-extracting a source produced fresh rows ordered by whatever the
 * extractor guessed and the operator's decision was gone. Publishing a revision
 * now carries those anchors across by page number.
 *
 * <p>Real MySQL: the guarantee is about which rows a stamp + reorder transaction
 * ends up writing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RevisionOrderCarryOverIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String OWNER = "carry-it-user-1";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IngestionRevisionPublisher publisher;

    private Long spaceId;
    private Long sourceId;

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
                + "(name, owner_subject, status, created_at, updated_at) VALUES ('重排保留空间', '"
                + OWNER + "', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        sourceId = insert("source", "INSERT INTO source "
                + "(space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                + "VALUES (" + spaceId + ", '扫描教材', 'UPLOAD', 'NEEDS_REVIEW', '" + OWNER
                + "', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
    }

    @AfterEach
    void cleanUp() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, List.of(OWNER));
    }

    // ==================== tests ====================

    /**
     * The operator put the cover page last and the index first. A re-extraction
     * hands the extractor's own 1,2,3 order to the same three page numbers, and
     * publishing has to put them back where the human said.
     */
    @Test
    void confirmedOrderIsCarriedIntoTheNewRevision() {
        Long previous = revision(1);
        insertPage(previous, 1, 2, "CONFIRMED");
        insertPage(previous, 2, 1, "CONFIRMED");
        insertPage(previous, 3, 3, "AUTO");
        pointSourceAt(previous);

        Long next = revision(2);
        insertPage(null, 1, 1, "AUTO");
        insertPage(null, 2, 2, "AUTO");
        insertPage(null, 3, 3, "AUTO");

        publisher.publish(spaceId, sourceId, next, LocalDateTime.now());

        Map<Integer, String> placement = new HashMap<>();
        for (SourcePage page : pagesOf(next)) {
            placement.put(page.getSourcePageNumber(),
                    page.getOrderStatus() + "@" + page.getPageOrder());
        }
        assertEquals(Map.of(1, "CONFIRMED@2", 2, "CONFIRMED@1", 3, "AUTO@3"), placement);
    }

    /** The carried pages keep their slots and the rest form a clean permutation. */
    @Test
    void publishingAnExtraPageKeepsAnchorsAndFillsTheFreeSlot() {
        Long previous = revision(1);
        insertPage(previous, 1, 3, "CONFIRMED");
        insertPage(previous, 2, 1, "CONFIRMED");
        pointSourceAt(previous);

        Long next = revision(2);
        insertPage(null, 1, 1, "AUTO");
        insertPage(null, 2, 2, "AUTO");
        insertPage(null, 3, 3, "AUTO");

        publisher.publish(spaceId, sourceId, next, LocalDateTime.now());

        List<SourcePage> pages = pagesOf(next);
        assertEquals(3, pages.size(), "every new page must be stamped with the revision");
        Map<Integer, Integer> orderPerPage = new HashMap<>();
        List<Integer> orders = new ArrayList<>();
        for (SourcePage page : pages) {
            orderPerPage.put(page.getSourcePageNumber(), page.getPageOrder());
            orders.add(page.getPageOrder());
        }
        assertEquals(Integer.valueOf(3), orderPerPage.get(1), "page 1 stays where the human put it");
        assertEquals(Integer.valueOf(1), orderPerPage.get(2), "page 2 stays where the human put it");
        assertEquals(Integer.valueOf(2), orderPerPage.get(3),
                "the unconfirmed page takes the only free slot, got " + orderPerPage);
        assertEquals(List.of(1, 2, 3), orders.stream().sorted().toList(),
                "orders must stay a permutation with no duplicate slot");
        assertEquals(2, confirmedCount(next), "only the two carried pages read as CONFIRMED");
    }

    /** An anchor that no longer fits the new page count is dropped, not forced. */
    @Test
    void anAnchorOutsideTheNewPageCountIsDropped() {
        Long previous = revision(1);
        insertPage(previous, 1, 9, "CONFIRMED");
        pointSourceAt(previous);

        Long next = revision(2);
        insertPage(null, 1, 1, "AUTO");
        insertPage(null, 2, 2, "AUTO");

        publisher.publish(spaceId, sourceId, next, LocalDateTime.now());

        List<Integer> orders = new ArrayList<>();
        for (SourcePage page : pagesOf(next)) {
            orders.add(page.getPageOrder());
        }
        assertEquals(List.of(1, 2), orders.stream().sorted().toList(),
                "no page may be pushed out of 1..N: " + orders);
        assertEquals(0, confirmedCount(next));
    }

    /** A first extraction has nothing to carry, so nothing is rewritten. */
    @Test
    void firstPublishKeepsTheExtractorsOrder() {
        Long first = revision(1);
        insertPage(null, 1, 1, "AUTO");
        insertPage(null, 2, 2, "AUTO");

        publisher.publish(spaceId, sourceId, first, LocalDateTime.now());

        assertEquals(List.of(1, 2), pageOrders(first));
        assertEquals(0, confirmedCount(first));
        assertEquals(first, currentRevisionOfSource());
    }

    // ==================== helpers ====================

    private Long revision(int version) {
        return insert("extraction_revision", "INSERT INTO extraction_revision "
                + "(space_id, source_id, version, status, created_at) VALUES (" + spaceId + ", "
                + sourceId + ", " + version + ", 'DRAFT', CURRENT_TIMESTAMP(6))");
    }

    private void pointSourceAt(Long revisionId) {
        jdbcTemplate.update("UPDATE source SET current_extraction_revision_id = ? WHERE id = ?",
                revisionId, sourceId);
    }

    private void insertPage(Long revisionId, int pageNumber, int pageOrder, String orderStatus) {
        jdbcTemplate.update("INSERT INTO source_page "
                        + "(space_id, source_id, extraction_revision_id, source_page_number, page_order, "
                        + "page_type, order_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'BODY', ?, "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, revisionId, pageNumber, pageOrder, orderStatus);
    }

    private List<SourcePage> pagesOf(Long revisionId) {
        return jdbcTemplate.query("SELECT * FROM source_page WHERE extraction_revision_id = ? "
                        + "AND space_id = ? ORDER BY page_order ASC, id ASC",
                (rs, i) -> {
                    SourcePage page = new SourcePage();
                    page.setId(rs.getLong("id"));
                    page.setSourcePageNumber((Integer) rs.getObject("source_page_number"));
                    page.setPageOrder(rs.getInt("page_order"));
                    page.setOrderStatus(rs.getString("order_status"));
                    return page;
                }, revisionId, spaceId);
    }

    private List<Integer> pageOrders(Long revisionId) {
        List<Integer> orders = new ArrayList<>();
        for (SourcePage page : pagesOf(revisionId)) {
            orders.add(page.getPageOrder());
        }
        return orders;
    }

    private int confirmedCount(Long revisionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_page WHERE extraction_revision_id = ? "
                        + "AND order_status = 'CONFIRMED'", Integer.class, revisionId);
    }

    private Long currentRevisionOfSource() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT current_extraction_revision_id FROM source WHERE id = ?", Long.class, sourceId);
        assertNotNull(value);
        return value;
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
                    "Refusing to run RevisionOrderCarryOverIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
