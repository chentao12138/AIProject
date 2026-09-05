package com.aistudy.server.spike.flyway;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE-003 STEP-03 + SPIKE-004 MICRO-07B-A + BUSINESS-001 + BUSINESS-002 —
 * Self-contained, repeatable Flyway integration test.
 *
 * The test drives Flyway explicitly through its Java API so that every
 * migration state is constructed inside the test itself:
 *   1. reset schema (clean)
 *   2. target V001 -> migrate()
 *   3. seed "V001升级前保留数据"
 *   4. record V001 checksum from flyway_schema_history (dynamic, not hardcoded)
 *   5. target latest -> migrate() -> V002 + V003 + V004 + V005 applied
 *   6. verify V001 checksum unchanged
 *   7. verify old data preserved
 *   8. verify both SPIKE tables exist (flyway_spike_record AND
 *      spike_space_membership)
 *   9. verify spike_space_membership schema: id / user_subject /
 *      space_id / status / created_at all present, plus the
 *      uk_spike_space_membership_user_space unique index on
 *      (user_subject, space_id)
 *  10. verify production learning_space table (V004, BUSINESS-001):
 *      id / name / description / owner_subject / status / created_at /
 *      updated_at columns plus the idx_learning_space_owner_subject
 *      owner index
 *  11. verify production source table (V005, BUSINESS-002):
 *      id / space_id / title / source_type / status /
 *      created_by_user_id / created_at / updated_at columns, the
 *      idx_source_space_created index, and the fk_source_space
 *      foreign key to learning_space(id)
 *  12. migrate again -> migrationsExecuted == 0
 *  13. history still V001 + V002 + V003 + V004 + V005 only
 *
 * Schema isolation (CRITICAL):
 *   - The test MUST run against aistudy_flyway_test ONLY.
 *   - Every destructive Flyway operation (clean()) is guarded by
 *     assertSchemaIsFlywayTest() which queries SELECT DATABASE() and
 *     throws IllegalStateException if it is anything other than
 *     aistudy_flyway_test.
 *   - The env-var family is FLYWAY_DB_URL / FLYWAY_DB_USERNAME /
 *     FLYWAY_DB_PASSWORD — never DB_URL. This physically prevents the
 *     early-run regression where Flyway clean() destroyed
 *     aistudy_spike.spike_record.
 *
 * Repeatability: every test method calls resetSchema() via @BeforeEach
 * so the test suite is self-contained, does not depend on prior test
 * state, and produces identical outcomes on every run.
 *
 * Does NOT hardcode any Flyway checksum value.
 */
@SpringBootTest
@ActiveProfiles("flyway-it")
class FlywayMigrationIntegrationTest {

    /**
     * The ONLY schema this test class is allowed to clean/migrate.
     * Every destructive Flyway call is preceded by a runtime check that
     * the JDBC URL actually resolves to this database. No exception.
     */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    @Autowired
    private Environment env;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Reset schema to a clean state before EVERY test. Flyway clean() removes
     * both the spike table and flyway_schema_history. Scoped to the current
     * schema only. Guarded to aistudy_flyway_test.
     */
    @BeforeEach
    void resetSchema() {
        assertSchemaIsFlywayTest();
        flyway().clean();
    }

    @AfterEach
    void cleanupTestData() {
        // The schema is reset at the start of every test; this is a
        // defensive no-op so test failures don't leave stray data.
    }

    // ==================== TEST A ====================

    @Test
    void freshDatabaseMigratesFromEmptyToLatest() {
        // (0) After @BeforeEach clean, no flyway_schema_history.
        Integer histCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_schema_history'",
                Integer.class);
        assertEquals(0, histCount,
                "flyway_schema_history must not exist after clean()");

        // (1) Migrate to latest. V005 is now the latest version.
        MigrateResult result = flyway().migrate();
        int applied = result.migrationsExecuted;
        assertEquals(5, applied,
                "Fresh migration must apply V001..V005 (actual: " + applied + ")");

        // (2) Verify flyway_schema_history has 5 rows: V001..V005.
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history",
                Integer.class);
        assertEquals(5, finalCount);

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT installed_rank, version, description, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals("001", String.valueOf(history.get(0).get("version")));
        assertEquals("002", String.valueOf(history.get(1).get("version")));
        assertEquals("003", String.valueOf(history.get(2).get("version")));
        assertEquals("004", String.valueOf(history.get(3).get("version")));
        assertEquals("005", String.valueOf(history.get(4).get("version")));
        assertEquals(1, history.get(0).get("installed_rank"));
        assertEquals(2, history.get(1).get("installed_rank"));
        assertEquals(3, history.get(2).get("installed_rank"));
        assertEquals(4, history.get(3).get("installed_rank"));
        assertEquals(5, history.get(4).get("installed_rank"));
        assertEquals(Boolean.TRUE, history.get(0).get("success"));
        assertEquals(Boolean.TRUE, history.get(1).get("success"));
        assertEquals(Boolean.TRUE, history.get(2).get("success"));
        assertEquals(Boolean.TRUE, history.get(3).get("success"));
        assertEquals(Boolean.TRUE, history.get(4).get("success"));

        // (3) Verify SPIKE-only flyway_spike_record table exists with both
        // V001 and V002 columns (unchanged from SPIKE-003 assertions).
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record'",
                Integer.class);
        assertEquals(1, tableCount);

        Map<String, Object> nameCol = jdbc.queryForMap(
                "SELECT CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record' AND COLUMN_NAME = 'name'");
        assertEquals("utf8mb4", nameCol.get("CHARACTER_SET_NAME"));

        Map<String, Object> noteCol = jdbc.queryForMap(
                "SELECT CHARACTER_SET_NAME, IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record' AND COLUMN_NAME = 'note'");
        assertEquals("utf8mb4", noteCol.get("CHARACTER_SET_NAME"));
        assertEquals("YES", noteCol.get("IS_NULLABLE"));

        // (4) Verify V003 SPIKE-only spike_space_membership table exists.
        Integer membershipTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_space_membership'",
                Integer.class);
        assertEquals(1, membershipTableCount,
                "V003 must create the spike_space_membership table");

        // (5) Verify V003 column structure — at minimum id / user_subject
        // / space_id / status / created_at must all be present. Lengths
        // and charsets are deliberately NOT asserted here; the SPIKE only
        // needs to prove the schema lands.
        List<String> expectedColumns = new ArrayList<>();
        expectedColumns.add("id");
        expectedColumns.add("user_subject");
        expectedColumns.add("space_id");
        expectedColumns.add("status");
        expectedColumns.add("created_at");
        List<String> actualColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_space_membership' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedColumns, actualColumns,
                "spike_space_membership must have exactly the expected columns in order");

        // (6) Verify the V003 unique index exists AND covers both
        // user_subject and space_id.
        List<Map<String, Object>> indexCols = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_space_membership' "
                        + "AND INDEX_NAME = 'uk_spike_space_membership_user_space' "
                        + "ORDER BY SEQ_IN_INDEX");
        assertEquals(2, indexCols.size(),
                "unique index must cover exactly 2 columns (actual: " + indexCols.size() + ")");
        List<String> indexColumnNames = new ArrayList<>();
        for (Map<String, Object> row : indexCols) {
            indexColumnNames.add(String.valueOf(row.get("COLUMN_NAME")));
        }
        assertTrue(indexColumnNames.contains("user_subject"),
                "unique index must include user_subject (actual: " + indexColumnNames + ")");
        assertTrue(indexColumnNames.contains("space_id"),
                "unique index must include space_id (actual: " + indexColumnNames + ")");

        // Sanity: the index must be marked as UNIQUE, not a plain index.
        Integer isUnique = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_space_membership' "
                        + "AND INDEX_NAME = 'uk_spike_space_membership_user_space' "
                        + "AND NON_UNIQUE = 0",
                Integer.class);
        assertTrue(isUnique > 0,
                "uk_spike_space_membership_user_space must be a UNIQUE index (NON_UNIQUE = 0)");

        // (7) Verify the V004 production learning_space table exists
        // (BUSINESS-001) with the full expected column set.
        Integer learningSpaceTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_space'",
                Integer.class);
        assertEquals(1, learningSpaceTableCount,
                "V004 must create the learning_space table");

        List<String> expectedLearningSpaceColumns = new ArrayList<>();
        expectedLearningSpaceColumns.add("id");
        expectedLearningSpaceColumns.add("name");
        expectedLearningSpaceColumns.add("description");
        expectedLearningSpaceColumns.add("owner_subject");
        expectedLearningSpaceColumns.add("status");
        expectedLearningSpaceColumns.add("created_at");
        expectedLearningSpaceColumns.add("updated_at");
        List<String> actualLearningSpaceColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_space' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedLearningSpaceColumns, actualLearningSpaceColumns,
                "learning_space must have exactly the expected columns in order");

        // (8) Verify the owner index exists on learning_space.owner_subject.
        List<String> ownerIndexColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_space' "
                        + "AND INDEX_NAME = 'idx_learning_space_owner_subject' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(1, ownerIndexColumns.size(),
                "owner index must cover exactly 1 column (actual: " + ownerIndexColumns.size() + ")");
        assertEquals("owner_subject", ownerIndexColumns.get(0),
                "owner index must cover owner_subject");

        // (9) Verify learning_space charset/collation matches the
        // project convention (utf8mb4 / utf8mb4_unicode_ci).
        Map<String, Object> lsCharset = jdbc.queryForMap(
                "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                        + "FROM information_schema.TABLES T "
                        + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                        + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                        + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = 'learning_space'");
        assertEquals("utf8mb4", lsCharset.get("CHARACTER_SET_NAME"));
        assertEquals("utf8mb4_unicode_ci", lsCharset.get("COLLATION_NAME"));

        // (10) Verify the V005 source table exists (BUSINESS-002) with
        // the full expected column set.
        Integer sourceTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source'",
                Integer.class);
        assertEquals(1, sourceTableCount,
                "V005 must create the source table");

        List<String> expectedSourceColumns = new ArrayList<>();
        expectedSourceColumns.add("id");
        expectedSourceColumns.add("space_id");
        expectedSourceColumns.add("title");
        expectedSourceColumns.add("source_type");
        expectedSourceColumns.add("status");
        expectedSourceColumns.add("created_by_user_id");
        expectedSourceColumns.add("created_at");
        expectedSourceColumns.add("updated_at");
        List<String> actualSourceColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedSourceColumns, actualSourceColumns,
                "source must have exactly the expected columns in order");

        // (11) Verify the source list index covers (space_id, created_at, id).
        List<String> sourceIndexColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source' "
                        + "AND INDEX_NAME = 'idx_source_space_created' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        List<String> expectedSourceIndex = new ArrayList<>();
        expectedSourceIndex.add("space_id");
        expectedSourceIndex.add("created_at");
        expectedSourceIndex.add("id");
        assertEquals(expectedSourceIndex, sourceIndexColumns,
                "idx_source_space_created must cover (space_id, created_at, id)");

        // (12) Verify the FK fk_source_space points at learning_space(id).
        List<Map<String, Object>> fkRows = jdbc.queryForList(
                "SELECT REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'source' "
                        + "  AND CONSTRAINT_NAME = 'fk_source_space'");
        assertEquals(1, fkRows.size(),
                "fk_source_space must exist (actual: " + fkRows.size() + " row(s))");
        assertEquals("learning_space", fkRows.get(0).get("REFERENCED_TABLE_NAME"));
        assertEquals("id", fkRows.get(0).get("REFERENCED_COLUMN_NAME"));

        // (13) Verify source charset/collation (utf8mb4 / utf8mb4_unicode_ci).
        Map<String, Object> srcCharset = jdbc.queryForMap(
                "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                        + "FROM information_schema.TABLES T "
                        + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                        + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                        + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = 'source'");
        assertEquals("utf8mb4", srcCharset.get("CHARACTER_SET_NAME"));
        assertEquals("utf8mb4_unicode_ci", srcCharset.get("COLLATION_NAME"));
    }

    // ==================== TEST B ====================

    @Test
    void existingV001DatabaseUpgradesToLatestAndPreservesData() {
        // (1) Migrate to V001 only.
        MigrateResult result1 = flyway("001").migrate();
        int appliedV1 = result1.migrationsExecuted;
        assertEquals(1, appliedV1,
                "target=001 must apply exactly V001 (actual: " + appliedV1 + ")");

        // (2) Verify only V001 in history.
        List<Map<String, Object>> h1 = jdbc.queryForList(
                "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(1, h1.size(), "only V001 expected after target=001 migrate");
        assertEquals("001", String.valueOf(h1.get(0).get("version")));
        assertEquals(Boolean.TRUE, h1.get(0).get("success"));

        // (3) Verify table exists WITHOUT note column (V002 not applied yet).
        Map<String, Object> noteCheck = jdbc.queryForMap(
                "SELECT COUNT(*) as cnt FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record' "
                        + "AND COLUMN_NAME = 'note'");
        assertEquals(0, ((Number) noteCheck.get("cnt")).intValue(),
                "note column must NOT exist after target=001 migrate");

        // (4) Also verify spike_space_membership does NOT exist yet (V003
        // not applied yet). This is a critical ordering assertion: V003
        // must only appear once we migrate past target=001.
        Integer membershipBeforeUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_space_membership'",
                Integer.class);
        assertEquals(0, membershipBeforeUpgrade,
                "spike_space_membership must NOT exist after target=001 migrate");

        // (5) Seed old data.
        jdbc.update("INSERT INTO flyway_spike_record (name) VALUES (?)",
                "V001升级前保留数据");
        Integer preId = jdbc.queryForObject(
                "SELECT id FROM flyway_spike_record WHERE name = ?",
                Integer.class, "V001升级前保留数据");
        assertTrue(preId > 0);

        // (6) Record V001 checksum BEFORE upgrade (dynamic read).
        Integer beforeChecksum = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '001'",
                Integer.class);
        assertNotNull(beforeChecksum,
                "V001 checksum must be present in flyway_schema_history before upgrade");

        // (7) Migrate to latest. V002, V003, V004, and V005 should all apply.
        MigrateResult result2 = flyway().migrate();
        int appliedV2 = result2.migrationsExecuted;
        assertEquals(4, appliedV2,
                "target=latest on V001-only db must apply V002, V003, V004, and V005 (actual: " + appliedV2 + ")");

        // (8) Verify history has 5 rows: V001..V005.
        List<Map<String, Object>> h2 = jdbc.queryForList(
                "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(5, h2.size());
        assertEquals("001", String.valueOf(h2.get(0).get("version")));
        assertEquals("002", String.valueOf(h2.get(1).get("version")));
        assertEquals("003", String.valueOf(h2.get(2).get("version")));
        assertEquals("004", String.valueOf(h2.get(3).get("version")));
        assertEquals("005", String.valueOf(h2.get(4).get("version")));
        assertEquals(Boolean.TRUE, h2.get(0).get("success"));
        assertEquals(Boolean.TRUE, h2.get(1).get("success"));
        assertEquals(Boolean.TRUE, h2.get(2).get("success"));
        assertEquals(Boolean.TRUE, h2.get(3).get("success"));
        assertEquals(Boolean.TRUE, h2.get(4).get("success"));

        // (9) V001 checksum UNCHANGED.
        Integer afterChecksum = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '001'",
                Integer.class);
        assertEquals(beforeChecksum, afterChecksum,
                "V001 checksum must be identical before and after V002/V003 upgrade");

        // (10) note column now exists (V002 effect).
        Integer noteExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record' AND COLUMN_NAME = 'note'",
                Integer.class);
        assertEquals(1, noteExists);

        // (11) spike_space_membership now exists (V003 effect).
        Integer membershipAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_space_membership'",
                Integer.class);
        assertEquals(1, membershipAfterUpgrade,
                "spike_space_membership must exist after upgrade to latest");

        // (11b) learning_space now exists (V004 effect, BUSINESS-001).
        Integer learningSpaceAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_space'",
                Integer.class);
        assertEquals(1, learningSpaceAfterUpgrade,
                "learning_space must exist after upgrade to latest");

        // (11c) source now exists (V005 effect, BUSINESS-002).
        Integer sourceAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source'",
                Integer.class);
        assertEquals(1, sourceAfterUpgrade,
                "source must exist after upgrade to latest");

        // (12) Old data still exists with V002's note column NULL.
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT id, name, note FROM flyway_spike_record WHERE id = ?", preId);
        assertEquals("V001升级前保留数据", old.get("name"));

        // (13) V002 + V003 + V004 + V005 idempotent on second migrate.
        MigrateResult result3 = flyway().migrate();
        assertEquals(0, result3.migrationsExecuted,
                "second migrate on already-latest schema must be a no-op");

        // (14) History still 5 rows.
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        assertEquals(5, finalCount);
    }

    // ==================== TEST C ====================

    @Test
    void latestDatabaseRequiresNoMigrationOnSecondMigrate() {
        // (1) Migrate to latest. V005 is the new latest version.
        MigrateResult first = flyway().migrate();
        assertEquals(5, first.migrationsExecuted,
                "first migrate must apply V001..V005 (actual: " + first.migrationsExecuted + ")");

        // (2) Confirm the SPIKE-only flyway_spike_record table has the
        // V002 note column.
        Map<String, Object> noteCol = jdbc.queryForMap(
                "SELECT CHARACTER_SET_NAME, IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record' "
                        + "AND COLUMN_NAME = 'note'");
        assertEquals("utf8mb4", noteCol.get("CHARACTER_SET_NAME"));

        // (2b) Also confirm the V003 spike_space_membership table exists.
        Integer membershipExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_space_membership'",
                Integer.class);
        assertEquals(1, membershipExists,
                "spike_space_membership must exist after fresh migrate to latest");

        // (2c) Also confirm the V004 learning_space table exists.
        Integer learningSpaceExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_space'",
                Integer.class);
        assertEquals(1, learningSpaceExists,
                "learning_space must exist after fresh migrate to latest");

        // (2d) Also confirm the V005 source table exists.
        Integer sourceExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source'",
                Integer.class);
        assertEquals(1, sourceExists,
                "source must exist after fresh migrate to latest");

        // (3) Migrate AGAIN. Must be a no-op.
        MigrateResult second = flyway().migrate();
        assertEquals(0, second.migrationsExecuted,
                "second migrate must execute 0 migrations (actual: " + second.migrationsExecuted + ")");

        // (4) History still exactly 5 rows.
        Integer historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        assertEquals(5, historyCount);

        // (5) Versions unchanged.
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        List<String> expected = new ArrayList<>();
        expected.add("001");
        expected.add("002");
        expected.add("003");
        expected.add("004");
        expected.add("005");
        assertEquals(expected, versions);
    }

    // ==================== helpers ====================

    /**
     * DESTRUCTIVE GUARD. Called immediately before every flyway().clean()
     * call. Queries the actual current database via JDBC and refuses to
     * proceed if it is not exactly {@value #EXPECTED_SCHEMA}.
     *
     * This guard is the safety net against the early-run regression
     * where Flyway clean() was invoked on aistudy_spike and destroyed
     * spike_record. The check is performed at runtime by resolving the
     * live JDBC connection's DATABASE(), not by string-matching the URL.
     *
     * @throws IllegalStateException if the current schema is not aistudy_flyway_test
     */
    private void assertSchemaIsFlywayTest() {
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (actual == null || !EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing Flyway clean: expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "SPIKE-003 Flyway tests MUST run against " + EXPECTED_SCHEMA
                            + " only. This is a destructive guard to prevent accidentally "
                            + "cleaning aistudy_spike or any other database.");
        }
    }

    /**
     * Build a Flyway instance from the Spring-managed datasource credentials.
     * We do NOT wire Flyway as a Spring bean because STEP-03 needs to
     * re-instantiate Flyway with different target values inside the same
     * test class (target 001 vs. default latest, plus idempotent no-op).
     *
     * NOTE on the target parameter: Flyway 11 does NOT accept an empty string
     * as "latest" (it tries to parse it as a version and throws
     * "Version may only contain 0..9 and ."). The correct way to say
     * "migrate to latest" is to simply NOT call .target() at all — the
     * default target is "latest".
     *
     * Every flyway() instance is guarded by assertSchemaIsFlywayTest() at
     * the caller before any destructive operation is performed.
     *
     * @param target null means default "latest"; a non-null string is
     *               passed to Flyway.configure().target(...).
     */
    private Flyway flyway(String target) {
        String url = env.getProperty("spring.datasource.url");
        String user = env.getProperty("spring.datasource.username");
        String pw = env.getProperty("spring.datasource.password");
        // Do NOT call .schemas(...). Flyway 11 by default operates on the
        // current JDBC-connection database (the one encoded in the URL path),
        // which is exactly what we want. Explicitly passing .schemas() would
        // cause Flyway to try CREATE DATABASE and fail with 1044 if the
        // connecting user lacks that privilege — even though the schema
        // already exists.
        var builder = Flyway.configure()
                .dataSource(url, user, pw)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .baselineOnMigrate(false);
        if (target != null) {
            builder = builder.target(target);
        }
        return builder.load();
    }

    private Flyway flyway() {
        return flyway(null);
    }
}
