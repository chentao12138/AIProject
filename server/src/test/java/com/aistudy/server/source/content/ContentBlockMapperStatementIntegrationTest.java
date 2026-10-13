package com.aistudy.server.source.content;

import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.source.content.dto.UpdateContentBlockRequest;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.content.service.ContentBlockService;
import com.aistudy.server.testsupport.OwnedSpaceReset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-25 / F-26 — executes the {@code ContentBlockMapper} statements and the
 * guarded update against the real mapper, on a real MySQL schema built by the
 * real migration chain.
 *
 * <p>This class exists because a bad predicate is invisible until it runs.
 * {@code selectBySpaceSourceAssetOwner} filtered on
 * {@code cb.source_asset_id} for its whole life: the interface compiled, the
 * mapper was wired, no test ever called it, and the first caller would have
 * received a 500 with "Unknown column 'cb.source_asset_id' in 'where clause'".
 * The offline column guard
 * ({@link ContentBlockMapperColumnGuardTest}) is the fast tripwire; executing
 * the statement is the proof that the join actually produces rows.
 *
 * <p>Runs under {@code flyway-it} only, against {@code aistudy_flyway_test},
 * with FK-derived cleanup scoped to its own subjects.
 */
@SpringBootTest
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentBlockMapperStatementIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String OWNER = "biz-e2e-block-owner";
    private static final String OTHER = "biz-e2e-block-other";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ContentBlockMapper contentBlockMapper;

    @Autowired
    private ContentBlockService contentBlockService;

    @BeforeEach
    void migrateAndClean() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(database)) {
            throw new IllegalStateException("Refusing to run against '" + database
                    + "'; expected '" + EXPECTED_SCHEMA + "'.");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        OwnedSpaceReset.forSubjects(jdbcTemplate, List.of(OWNER, OTHER));
    }

    /** space → source → asset → page → block, the whole FK chain, as fixtures. */
    private Fixture fixture(String ownerSubject) {
        jdbcTemplate.update("INSERT INTO learning_space (name, description, owner_subject,"
                        + " status, created_at, updated_at) VALUES (?, NULL, ?, 'ACTIVE',"
                        + " CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                "block fixture " + ownerSubject, ownerSubject);
        Long spaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject = ? ORDER BY id DESC LIMIT 1",
                Long.class, ownerSubject);

        insertSource(spaceId, "source A");
        Long sourceA = lastSourceId(spaceId);
        Long assetA = insertAsset(spaceId, sourceA, "a-note.txt");
        Long pageA = insertPage(spaceId, sourceA, assetA, null);
        Long blockA = insertBlock(spaceId, sourceA, pageA, null, "alpha block");

        insertSource(spaceId, "source B");
        Long sourceB = lastSourceId(spaceId);
        Long pageB = insertPage(spaceId, sourceB, null, null);
        Long blockB = insertBlock(spaceId, sourceB, pageB, null, "bravo block");

        return new Fixture(spaceId, sourceA, assetA, pageA, blockA, sourceB, blockB);
    }

    private void insertSource(Long spaceId, String title) {
        jdbcTemplate.update("INSERT INTO source (space_id, title, source_type, status,"
                        + " review_status, created_by_user_id, created_at, updated_at)"
                        + " VALUES (?, ?, 'DESKTOP_UPLOAD', 'NEEDS_REVIEW', NULL, ?, "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title, OWNER);
    }

    private Long lastSourceId(Long spaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source WHERE space_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId);
    }

    private Long insertAsset(Long spaceId, Long sourceId, String name) {
        jdbcTemplate.update("INSERT INTO source_asset (space_id, source_id, asset_role,"
                        + " original_name, storage_key, mime_type, size_bytes, sha256, created_at)"
                        + " VALUES (?, ?, 'ORIGINAL_FILE', ?, ?, 'text/plain', 12, '0', "
                        + "CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, name, "2026/10/" + name + "-" + System.nanoTime());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source_asset WHERE source_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, sourceId);
    }

    private Long insertPage(Long spaceId, Long sourceId, Long assetId, Long revisionId) {
        jdbcTemplate.update("INSERT INTO source_page (space_id, source_id, source_asset_id,"
                        + " page_order, page_type, order_status, extracted_text,"
                        + " extraction_revision_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, 'BODY', 'AUTO', 'page text', ?, "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, assetId, revisionId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source_page WHERE source_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, sourceId);
    }

    private Long insertBlock(Long spaceId, Long sourceId, Long pageId, Long revisionId, String text) {
        jdbcTemplate.update("INSERT INTO content_block (space_id, source_id, source_page_id,"
                        + " block_type, sort_order, normalized_text, extraction_revision_id,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'PARAGRAPH', 0, ?, ?, CURRENT_TIMESTAMP(6),"
                        + " CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, pageId, text, revisionId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM content_block WHERE source_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, sourceId);
    }

    /**
     * F-25 proper: the asset-scoped block read must execute and return the
     * blocks whose PAGE carries the asset. Under the old predicate this method
     * threw a SQLException; under a naive "just drop the filter" rewrite it
     * would wrongly return blocks of other assets too.
     */
    @Test
    void assetBlockReadExecutesAndResolvesThroughThePage() {
        Fixture fixture = fixture(OWNER);

        List<ContentBlock> blocks = contentBlockMapper.selectBySpaceSourceAssetOwner(
                fixture.assetA(), fixture.spaceId(), OWNER);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getId()).isEqualTo(fixture.blockA());
        assertThat(blocks.get(0).getNormalizedText()).isEqualTo("alpha block");
        assertThat(blocks.get(0).getSourceId()).isEqualTo(fixture.sourceA());

        // A foreign owner sees nothing rather than leaking the row's existence.
        assertThat(contentBlockMapper.selectBySpaceSourceAssetOwner(
                fixture.assetA(), fixture.spaceId(), OTHER)).isEmpty();
    }

    /** The other statements of this mapper must execute on the real schema too. */
    @Test
    void otherQualifiedStatementsStillExecuteAfterTheColumnFix() {
        Fixture fixture = fixture(OWNER);

        assertThat(contentBlockMapper.selectBySpaceSourceOwner(
                fixture.spaceId(), fixture.sourceA(), OWNER)).hasSize(1);
        assertThat(contentBlockMapper.selectBySpaceSourcePageOwner(
                fixture.spaceId(), fixture.sourceA(), fixture.pageA(), OWNER)).hasSize(1);
        assertThat(contentBlockMapper.selectByIdAndSpaceSourceOwner(
                fixture.blockA(), fixture.spaceId(), fixture.sourceA(), OWNER)).isNotNull();
        assertThat(contentBlockMapper.selectByIdAndSpace(
                fixture.blockA(), fixture.spaceId())).isNotNull();
        assertThat(contentBlockMapper.stampRevisionOnSourceBlocks(
                fixture.spaceId(), fixture.sourceA(), 999_001L, LocalDateTime.now())).isEqualTo(1);
        assertThat(contentBlockMapper.selectByRevision(999_001L, fixture.spaceId())).hasSize(1);
    }

    /**
     * F-26 at the write side: addressing a block through another source of the
     * SAME space is a 404 and leaves the row untouched — the old lookup proved
     * only space and owner, so it wrote anyway.
     */
    @Test
    void blockOfAnotherSourceInTheSameSpaceCannotBeEditedThroughThisSource() {
        Fixture fixture = fixture(OWNER);

        assertThatThrownBy(() -> contentBlockService.update(OWNER, fixture.spaceId(),
                fixture.sourceA(), fixture.blockB(), new UpdateContentBlockRequest("tampered", null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(404);
                    assertThat(ex.code()).isEqualTo("NOT_FOUND");
                });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT normalized_text FROM content_block WHERE id = ?",
                String.class, fixture.blockB())).isEqualTo("bravo block");
    }

    /**
     * F-26 live-content half, on real rows: the current revision of a
     * PUBLISHED source is what readers cite, so it is read-only in place with a
     * stable code; the same block of a draft revision stays correctable.
     */
    @Test
    void editingTheCurrentRevisionOfAPublishedSourceIsRefusedButADraftIsEditable() {
        Fixture fixture = fixture(OWNER);
        jdbcTemplate.update("INSERT INTO extraction_revision (space_id, source_id, version,"
                        + " status, created_at) VALUES (?, ?, 1, 'NEEDS_REVIEW', CURRENT_TIMESTAMP(6))",
                fixture.spaceId(), fixture.sourceA());
        Long revision = jdbcTemplate.queryForObject(
                "SELECT id FROM extraction_revision WHERE source_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, fixture.sourceA());
        jdbcTemplate.update("UPDATE source_page SET extraction_revision_id = ? WHERE id = ?",
                revision, fixture.pageA());
        jdbcTemplate.update("UPDATE content_block SET extraction_revision_id = ? WHERE id = ?",
                revision, fixture.blockA());
        jdbcTemplate.update("UPDATE source SET status = 'PUBLISHED', current_extraction_revision_id = ?"
                + " WHERE id = ?", revision, fixture.sourceA());

        assertThatThrownBy(() -> contentBlockService.update(OWNER, fixture.spaceId(),
                fixture.sourceA(), fixture.blockA(), new UpdateContentBlockRequest("rewritten", null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(409);
                    assertThat(ex.code())
                            .isEqualTo("CONTENT_BLOCK_PUBLISHED_REVISION_READONLY");
                });

        // Same block, source back under review: the correction flow works again.
        jdbcTemplate.update("UPDATE source SET status = 'NEEDS_REVIEW' WHERE id = ?", fixture.sourceA());
        ContentBlock updated = contentBlockService.update(OWNER, fixture.spaceId(),
                fixture.sourceA(), fixture.blockA(), new UpdateContentBlockRequest("rewritten", null, null));
        assertThat(updated.getNormalizedText()).isEqualTo("rewritten");
    }

    /** space, sourceA, assetA, pageA, blockA plus sourceB and its block. */
    private record Fixture(Long spaceId, Long sourceA, Long assetA, Long pageA, Long blockA,
                           Long sourceB, Long blockB) {
    }
}
