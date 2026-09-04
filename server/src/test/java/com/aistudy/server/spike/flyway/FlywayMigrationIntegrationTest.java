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
 * SPIKE-003 STEP-03 — Self-contained, repeatable Flyway integration test.
 *
 * The test drives Flyway explicitly through its Java API so that every
 * migration state is constructed inside the test itself:
 *   1. reset schema (clean)
 *   2. target V001 -> migrate()
 *   3. seed "V001升级前保留数据"
 *   4. record V001 checksum from flyway_schema_history (dynamic, not hardcoded)
 *   5. target latest -> migrate() -> V002 applied
 *   6. verify V001 checksum unchanged
 *   7. verify old data preserved
 *   8. migrate again -> migrationsExecuted == 0
 *   9. history still V001 + V002 only
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

        // (1) Migrate to latest.
        MigrateResult result = flyway().migrate();
        int applied = result.migrationsExecuted;
        assertEquals(2, applied,
                "Fresh migration must apply both V001 and V002 (actual: " + applied + ")");

        // (2) Verify flyway_schema_history.
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history",
                Integer.class);
        assertEquals(2, finalCount);

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT installed_rank, version, description, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals("001", String.valueOf(history.get(0).get("version")));
        assertEquals("002", String.valueOf(history.get(1).get("version")));
        assertEquals(1, history.get(0).get("installed_rank"));
        assertEquals(2, history.get(1).get("installed_rank"));
        assertEquals(Boolean.TRUE, history.get(0).get("success"));
        assertEquals(Boolean.TRUE, history.get(1).get("success"));

        // (3) Verify SPIKE-only table exists with both V001 and V002 columns.
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
    }

    // ==================== TEST B ====================

    @Test
    void existingV001DatabaseUpgradesToV002AndPreservesData() {
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

        // (3) Verify table exists WITHOUT note column.
        Map<String, Object> noteCheck = jdbc.queryForMap(
                "SELECT COUNT(*) as cnt FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record' "
                        + "AND COLUMN_NAME = 'note'");
        assertEquals(0, ((Number) noteCheck.get("cnt")).intValue(),
                "note column must NOT exist after target=001 migrate");

        // (4) Seed old data.
        jdbc.update("INSERT INTO flyway_spike_record (name) VALUES (?)",
                "V001升级前保留数据");
        Integer preId = jdbc.queryForObject(
                "SELECT id FROM flyway_spike_record WHERE name = ?",
                Integer.class, "V001升级前保留数据");
        assertTrue(preId > 0);

        // (5) Record V001 checksum BEFORE upgrade (dynamic read).
        Integer beforeChecksum = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '001'",
                Integer.class);
        assertNotNull(beforeChecksum,
                "V001 checksum must be present in flyway_schema_history before upgrade");

        // (6) Migrate to latest. Only V002 should apply.
        MigrateResult result2 = flyway().migrate();
        int appliedV2 = result2.migrationsExecuted;
        assertEquals(1, appliedV2,
                "target=latest on V001-only db must apply only V002 (actual: " + appliedV2 + ")");

        // (7) Verify V002 in history.
        List<Map<String, Object>> h2 = jdbc.queryForList(
                "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(2, h2.size());
        assertEquals("001", String.valueOf(h2.get(0).get("version")));
        assertEquals("002", String.valueOf(h2.get(1).get("version")));
        assertEquals(Boolean.TRUE, h2.get(0).get("success"));
        assertEquals(Boolean.TRUE, h2.get(1).get("success"));

        // (8) V001 checksum UNCHANGED.
        Integer afterChecksum = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '001'",
                Integer.class);
        assertEquals(beforeChecksum, afterChecksum,
                "V001 checksum must be identical before and after V002 upgrade");

        // (9) note column now exists.
        Integer noteExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record' AND COLUMN_NAME = 'note'",
                Integer.class);
        assertEquals(1, noteExists);

        // (10) Old data still exists.
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT id, name, note FROM flyway_spike_record WHERE id = ?", preId);
        assertEquals("V001升级前保留数据", old.get("name"));

        // (11) V002 idempotent.
        MigrateResult result3 = flyway().migrate();
        assertEquals(0, result3.migrationsExecuted,
                "second migrate on already-latest schema must be a no-op");

        // (12) History still 2 rows.
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        assertEquals(2, finalCount);
    }

    // ==================== TEST C ====================

    @Test
    void latestDatabaseRequiresNoMigrationOnSecondMigrate() {
        // (1) Migrate to latest.
        MigrateResult first = flyway().migrate();
        assertEquals(2, first.migrationsExecuted,
                "first migrate must apply V001 and V002 (actual: " + first.migrationsExecuted + ")");

        // (2) Confirm the SPIKE-only table has the note column.
        Map<String, Object> noteCol = jdbc.queryForMap(
                "SELECT CHARACTER_SET_NAME, IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_spike_record' "
                        + "AND COLUMN_NAME = 'note'");
        assertEquals("utf8mb4", noteCol.get("CHARACTER_SET_NAME"));

        // (3) Migrate AGAIN. Must be a no-op.
        MigrateResult second = flyway().migrate();
        assertEquals(0, second.migrationsExecuted,
                "second migrate must execute 0 migrations (actual: " + second.migrationsExecuted + ")");

        // (4) History still exactly 2 rows.
        Integer historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        assertEquals(2, historyCount);

        // (5) Versions unchanged.
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        List<String> expected = new ArrayList<>();
        expected.add("001");
        expected.add("002");
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
