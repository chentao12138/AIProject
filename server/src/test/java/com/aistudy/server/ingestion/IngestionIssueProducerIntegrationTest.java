package com.aistudy.server.ingestion;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.ingestion.issue.service.IngestionIssueService;
import com.aistudy.server.ingestion.issue.service.StructuringReviewSignals;
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
 * Audit F-06 — the ordering and outline review issues are actually produced.
 *
 * <p>Only OCR confidence and partial extraction ever became issues, so the
 * requirement that a low ordering or outline confidence goes to human review had
 * no path at all: a source could be published with a scrambled page order nobody
 * was told about. Real MySQL, plus the resolve/ignore verbs a reviewer uses.
 *
 * <p>{@code max-issues-per-revision} is pinned to 2 here so the cap and its
 * "plus N more" wording are both observable.
 */
@SpringBootTest(properties = "aistudy.ingestion.review.max-issues-per-revision=2")
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionIssueProducerIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String OWNER = "iss-it-user-1";
    private static final List<String> SUBJECTS = List.of(OWNER);
    private static final String ISSUES = "/api/v1/spaces/{spaceId}/ingestion-issues";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @Autowired
    private StructuringReviewSignals structuringReviewSignals;

    @Autowired
    private IngestionIssueService ingestionIssueService;

    private Long spaceId;
    private Long sourceId;
    private Long revisionId;
    private Long jobId;

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
        spaceId = insert("learning_space", "INSERT INTO learning_space "
                + "(name, owner_subject, status, created_at, updated_at) "
                + "VALUES ('摄取问题空间', '" + OWNER + "', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        sourceId = insert("source", "INSERT INTO source "
                + "(space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                + "VALUES (" + spaceId + ", '教材', 'UPLOAD', 'ACTIVE', '" + OWNER
                + "', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        revisionId = insert("extraction_revision", "INSERT INTO extraction_revision "
                + "(space_id, source_id, version, status, created_at) VALUES (" + spaceId + ", "
                + sourceId + ", 1, 'NEEDS_REVIEW', CURRENT_TIMESTAMP(6))");
        jobId = insert("ingestion_job", "INSERT INTO ingestion_job "
                + "(space_id, source_id, status, stage, created_by_user_id, created_at, updated_at) "
                + "VALUES (" + spaceId + ", " + sourceId + ", 'NEEDS_REVIEW', 'REVIEW', '" + OWNER
                + "', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
    }

    @AfterEach
    void cleanUp() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, SUBJECTS);
    }

    // ==================== tests ====================

    /**
     * One page below the threshold, one above it, one with no confidence at all
     * (a text-layer PDF never measured ordering): only the low one becomes an
     * issue, and the outline count produces one aggregate issue.
     */
    @Test
    void lowConfidencePagesAndOutlineBecomeReviewIssues() throws Exception {
        Long lowPage = insertPage(1, 0.4);
        insertPage(2, 0.95);
        insertPage(3, null);
        insertOutline(0.2);
        insertOutline(null);

        StructuringReviewSignals.Signals signals =
                structuringReviewSignals.scan(spaceId, revisionId);
        assertEquals(List.of(lowPage), signals.lowConfidencePageIds());
        assertEquals(0, signals.omittedPageCount());
        assertEquals(1, signals.lowConfidenceOutlineNodes());

        int written = ingestionIssueService.recordOrderingIssues(spaceId, sourceId, jobId,
                revisionId, signals.lowConfidencePageIds(), signals.omittedPageCount(),
                signals.lowConfidenceOutlineNodes());
        assertEquals(2, written);

        mockMvc.perform(get(ISSUES, spaceId).param("jobId", String.valueOf(jobId))
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // The reviewer list must show the page the ordering complaint is about,
        // so the association is checked on the rows the endpoint just returned.
        assertEquals(List.of("MEDIUM", "MEDIUM"), jdbcTemplate.queryForList(
                "SELECT severity FROM ingestion_issue WHERE source_page_id IS NOT NULL "
                        + "OR issue_type = 'OUTLINE_LOW_CONFIDENCE' ORDER BY severity", String.class));
        assertEquals(List.of(lowPage), jdbcTemplate.queryForList(
                "SELECT source_page_id FROM ingestion_issue "
                        + "WHERE issue_type = 'ORDER_LOW_CONFIDENCE'", Long.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingestion_issue WHERE issue_type = 'OUTLINE_LOW_CONFIDENCE' "
                        + "AND status = 'OPEN' AND source_page_id IS NULL", Integer.class).longValue());
    }

    /**
     * Resolving over HTTP used to answer 500 for every real login: the controller
     * parsed the JWT subject as the numeric resolver id, and subjects are strings
     * such as {@code dev}.
     */
    @Test
    void resolveAcceptsANonNumericSubject() throws Exception {
        Long lowPage = insertPage(1, 0.1);
        StructuringReviewSignals.Signals signals =
                structuringReviewSignals.scan(spaceId, revisionId);
        ingestionIssueService.recordOrderingIssues(spaceId, sourceId, jobId, revisionId,
                signals.lowConfidencePageIds(), signals.omittedPageCount(),
                signals.lowConfidenceOutlineNodes());
        Long issueId = jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_issue WHERE issue_type = 'ORDER_LOW_CONFIDENCE' "
                        + "AND source_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, sourceId);
        assertNotNull(lowPage);

        mockMvc.perform(post(ISSUES + "/{issueId}/resolve", spaceId, issueId)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedBy").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(post(ISSUES + "/{issueId}/ignore", spaceId, issueId)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));

        mockMvc.perform(post(ISSUES + "/{issueId}/resolve", spaceId, 987654321L)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    /** The cap bounds the rows but says how many pages it left out. */
    @Test
    void pageIssuesAreCappedAndReportTheRemainder() {
        insertPage(1, 0.1);
        insertPage(2, 0.2);
        insertPage(3, 0.3);

        StructuringReviewSignals.Signals signals =
                structuringReviewSignals.scan(spaceId, revisionId);
        assertEquals(2, signals.lowConfidencePageIds().size());
        assertEquals(1, signals.omittedPageCount());

        ingestionIssueService.recordOrderingIssues(spaceId, sourceId, jobId, revisionId,
                signals.lowConfidencePageIds(), signals.omittedPageCount(),
                signals.lowConfidenceOutlineNodes());

        List<String> messages = jdbcTemplate.queryForList(
                "SELECT safe_message FROM ingestion_issue WHERE issue_type = 'ORDER_LOW_CONFIDENCE'",
                String.class);
        assertEquals(2, messages.size());
        for (String message : messages) {
            assertNotNull(message);
            assertEquals(true, message.contains("plus 1 more pages"), message);
        }
    }

    // ==================== helpers ====================

    private String token() {
        String token = jwtAccessTokenService.issueAccessToken(OWNER);
        assertNotNull(token, "token must not be null");
        return token;
    }

    private Long insertPage(int pageNumber, Double orderConfidence) {
        return insert("source_page", "INSERT INTO source_page "
                + "(space_id, source_id, extraction_revision_id, source_page_number, page_order, "
                + "page_type, order_status, order_confidence, created_at, updated_at) VALUES ("
                + spaceId + ", " + sourceId + ", " + revisionId + ", " + pageNumber + ", "
                + pageNumber + ", 'BODY', 'AUTO', " + (orderConfidence == null ? "NULL" : orderConfidence)
                + ", CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
    }

    private Long insertOutline(Double confidence) {
        return insert("source_outline_node", "INSERT INTO source_outline_node "
                + "(space_id, source_id, extraction_revision_id, node_type, title, sort_order, "
                + "status, confidence, created_at, updated_at) VALUES (" + spaceId + ", " + sourceId
                + ", " + revisionId + ", 'CHAPTER', '第1章 绪论', 0, 'DRAFT', "
                + (confidence == null ? "NULL" : confidence)
                + ", CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
    }

    private Long insert(String table, String sql) {
        jdbcTemplate.update(sql);
        return jdbcTemplate.queryForObject("SELECT id FROM " + table + " ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run IngestionIssueProducerIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }
}
