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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE-003 STEP-03 + SPIKE-004 MICRO-07B-A + BUSINESS-001 + BUSINESS-002
 * + BUSINESS-003 — Self-contained, repeatable Flyway integration test.
 *
 * The test drives Flyway explicitly through its Java API so that every
 * migration state is constructed inside the test itself:
 *   1. reset schema (clean)
 *   2. target V001 -> migrate()
 *   3. seed "V001升级前保留数据"
 *   4. record V001 checksum from flyway_schema_history (dynamic, not hardcoded)
 *   5. target latest -> migrate() -> V002..V007 applied
 *   6. verify V001 checksum unchanged
 *   7. verify old data preserved
 *   8. verify both SPIKE tables exist (flyway_spike_record AND
 *      spike_space_membership)
 *   9. verify spike_space_membership schema: id / user_subject /
 *      space_id / status / created_at all present, plus the
 *      uk_spike_space_membership_user_space unique index on
 *      (user_subject, space_id)
 *  10. verify production learning_space table (V004, BUSINESS-001)
 *  11. verify production source table (V005, BUSINESS-002)
 *  12. verify production knowledge_category table (V006, BUSINESS-003):
 *      columns, idx_knowledge_category_space_parent_sort, FKs
 *  13. verify production knowledge_point table (V007, BUSINESS-003):
 *      columns, indexes, FKs, charset
 *  14. migrate again -> migrationsExecuted == 0
 *  15. history still V001..V007 only
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

        // (1) Migrate to latest. V007 is now the latest version.
        MigrateResult result = flyway().migrate();
        int applied = result.migrationsExecuted;
        assertEquals(7, applied,
                "Fresh migration must apply V001..V007 (actual: " + applied + ")");

        // (2) Verify flyway_schema_history has 7 rows: V001..V007.
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history",
                Integer.class);
        assertEquals(7, finalCount);

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT installed_rank, version, description, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals("001", String.valueOf(history.get(0).get("version")));
        assertEquals("002", String.valueOf(history.get(1).get("version")));
        assertEquals("003", String.valueOf(history.get(2).get("version")));
        assertEquals("004", String.valueOf(history.get(3).get("version")));
        assertEquals("005", String.valueOf(history.get(4).get("version")));
        assertEquals("006", String.valueOf(history.get(5).get("version")));
        assertEquals("007", String.valueOf(history.get(6).get("version")));
        assertEquals(1, history.get(0).get("installed_rank"));
        assertEquals(2, history.get(1).get("installed_rank"));
        assertEquals(3, history.get(2).get("installed_rank"));
        assertEquals(4, history.get(3).get("installed_rank"));
        assertEquals(5, history.get(4).get("installed_rank"));
        assertEquals(6, history.get(5).get("installed_rank"));
        assertEquals(7, history.get(6).get("installed_rank"));
        assertEquals(Boolean.TRUE, history.get(0).get("success"));
        assertEquals(Boolean.TRUE, history.get(1).get("success"));
        assertEquals(Boolean.TRUE, history.get(2).get("success"));
        assertEquals(Boolean.TRUE, history.get(3).get("success"));
        assertEquals(Boolean.TRUE, history.get(4).get("success"));
        assertEquals(Boolean.TRUE, history.get(5).get("success"));
        assertEquals(Boolean.TRUE, history.get(6).get("success"));

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

        // (14) Verify the V006 knowledge_category table (BUSINESS-003).
        Integer categoryTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_category'",
                Integer.class);
        assertEquals(1, categoryTableCount,
                "V006 must create the knowledge_category table");

        List<String> expectedCategoryColumns = new ArrayList<>();
        expectedCategoryColumns.add("id");
        expectedCategoryColumns.add("space_id");
        expectedCategoryColumns.add("parent_id");
        expectedCategoryColumns.add("name");
        expectedCategoryColumns.add("description");
        expectedCategoryColumns.add("sort_order");
        expectedCategoryColumns.add("created_at");
        expectedCategoryColumns.add("updated_at");
        List<String> actualCategoryColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_category' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedCategoryColumns, actualCategoryColumns,
                "knowledge_category must have exactly the expected columns in order");

        // (15) Verify knowledge_category index (space_id, parent_id, sort_order, id).
        List<String> categoryIndexColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_category' "
                        + "AND INDEX_NAME = 'idx_knowledge_category_space_parent_sort' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        List<String> expectedCategoryIndex = new ArrayList<>();
        expectedCategoryIndex.add("space_id");
        expectedCategoryIndex.add("parent_id");
        expectedCategoryIndex.add("sort_order");
        expectedCategoryIndex.add("id");
        assertEquals(expectedCategoryIndex, categoryIndexColumns,
                "idx_knowledge_category_space_parent_sort must cover (space_id, parent_id, sort_order, id)");

        // (16) Verify knowledge_category FKs: space_id -> learning_space, parent_id -> knowledge_category.
        // Build a Map keyed by CONSTRAINT_NAME so the assertions do
        // NOT depend on information_schema return order.
        List<Map<String, Object>> categoryFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'knowledge_category' "
                        + "  AND CONSTRAINT_NAME IN ('fk_knowledge_category_space', 'fk_knowledge_category_parent')");
        assertEquals(2, categoryFkRows.size(),
                "knowledge_category must have exactly 2 FKs (actual: " + categoryFkRows.size() + ")");

        Map<String, Map<String, Object>> categoryFkByName = new HashMap<>();
        for (Map<String, Object> row : categoryFkRows) {
            categoryFkByName.put(String.valueOf(row.get("CONSTRAINT_NAME")), row);
        }
        assertEquals("learning_space",
                categoryFkByName.get("fk_knowledge_category_space").get("REFERENCED_TABLE_NAME"),
                "fk_knowledge_category_space must reference learning_space");
        assertEquals("id",
                categoryFkByName.get("fk_knowledge_category_space").get("REFERENCED_COLUMN_NAME"),
                "fk_knowledge_category_space must reference learning_space(id)");
        assertEquals("knowledge_category",
                categoryFkByName.get("fk_knowledge_category_parent").get("REFERENCED_TABLE_NAME"),
                "fk_knowledge_category_parent must reference knowledge_category");
        assertEquals("id",
                categoryFkByName.get("fk_knowledge_category_parent").get("REFERENCED_COLUMN_NAME"),
                "fk_knowledge_category_parent must reference knowledge_category(id)");

        // (17) Verify the V007 knowledge_point table (BUSINESS-003).
        Integer pointTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point'",
                Integer.class);
        assertEquals(1, pointTableCount,
                "V007 must create the knowledge_point table");

        List<String> expectedPointColumns = new ArrayList<>();
        expectedPointColumns.add("id");
        expectedPointColumns.add("space_id");
        expectedPointColumns.add("category_id");
        expectedPointColumns.add("title");
        expectedPointColumns.add("summary");
        expectedPointColumns.add("content");
        expectedPointColumns.add("origin_type");
        expectedPointColumns.add("status");
        expectedPointColumns.add("difficulty");
        expectedPointColumns.add("created_by_user_id");
        expectedPointColumns.add("created_at");
        expectedPointColumns.add("updated_at");
        expectedPointColumns.add("published_at");
        expectedPointColumns.add("deleted_at");
        List<String> actualPointColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedPointColumns, actualPointColumns,
                "knowledge_point must have exactly the expected columns in order");

        // (18) Verify knowledge_point indexes.
        List<String> pointStatusCategoryIndex = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point' "
                        + "AND INDEX_NAME = 'idx_knowledge_point_space_status_category' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        List<String> expectedStatusCategoryIndex = new ArrayList<>();
        expectedStatusCategoryIndex.add("space_id");
        expectedStatusCategoryIndex.add("status");
        expectedStatusCategoryIndex.add("category_id");
        assertEquals(expectedStatusCategoryIndex, pointStatusCategoryIndex,
                "idx_knowledge_point_space_status_category must cover (space_id, status, category_id)");

        List<String> pointCreatedIndex = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point' "
                        + "AND INDEX_NAME = 'idx_knowledge_point_space_created' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        List<String> expectedCreatedIndex = new ArrayList<>();
        expectedCreatedIndex.add("space_id");
        expectedCreatedIndex.add("created_at");
        expectedCreatedIndex.add("id");
        assertEquals(expectedCreatedIndex, pointCreatedIndex,
                "idx_knowledge_point_space_created must cover (space_id, created_at, id)");

        // (19) Verify knowledge_point FKs: space_id -> learning_space, category_id -> knowledge_category.
        // Build a Map keyed by CONSTRAINT_NAME so the assertions do
        // NOT depend on information_schema return order.
        List<Map<String, Object>> pointFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'knowledge_point' "
                        + "  AND CONSTRAINT_NAME IN ('fk_knowledge_point_space', 'fk_knowledge_point_category')");
        assertEquals(2, pointFkRows.size(),
                "knowledge_point must have exactly 2 FKs (actual: " + pointFkRows.size() + ")");

        Map<String, Map<String, Object>> pointFkByName = new HashMap<>();
        for (Map<String, Object> row : pointFkRows) {
            pointFkByName.put(String.valueOf(row.get("CONSTRAINT_NAME")), row);
        }
        assertEquals("learning_space",
                pointFkByName.get("fk_knowledge_point_space").get("REFERENCED_TABLE_NAME"),
                "fk_knowledge_point_space must reference learning_space");
        assertEquals("id",
                pointFkByName.get("fk_knowledge_point_space").get("REFERENCED_COLUMN_NAME"),
                "fk_knowledge_point_space must reference learning_space(id)");
        assertEquals("knowledge_category",
                pointFkByName.get("fk_knowledge_point_category").get("REFERENCED_TABLE_NAME"),
                "fk_knowledge_point_category must reference knowledge_category");
        assertEquals("id",
                pointFkByName.get("fk_knowledge_point_category").get("REFERENCED_COLUMN_NAME"),
                "fk_knowledge_point_category must reference knowledge_category(id)");

        // (20) Verify knowledge tables charset/collation (utf8mb4 / utf8mb4_unicode_ci).
        for (String tableName : new String[]{"knowledge_category", "knowledge_point"}) {
            Map<String, Object> charset = jdbc.queryForMap(
                    "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                            + "FROM information_schema.TABLES T "
                            + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                            + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                            + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = ?",
                    tableName);
            assertEquals("utf8mb4", charset.get("CHARACTER_SET_NAME"),
                    tableName + " charset must be utf8mb4");
            assertEquals("utf8mb4_unicode_ci", charset.get("COLLATION_NAME"),
                    tableName + " collation must be utf8mb4_unicode_ci");
        }
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

        // (7) Migrate to latest. V002..V007 should all apply.
        MigrateResult result2 = flyway().migrate();
        int appliedV2 = result2.migrationsExecuted;
        assertEquals(6, appliedV2,
                "target=latest on V001-only db must apply V002..V007 (actual: " + appliedV2 + ")");

        // (8) Verify history has 7 rows: V001..V007.
        List<Map<String, Object>> h2 = jdbc.queryForList(
                "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(7, h2.size());
        assertEquals("001", String.valueOf(h2.get(0).get("version")));
        assertEquals("002", String.valueOf(h2.get(1).get("version")));
        assertEquals("003", String.valueOf(h2.get(2).get("version")));
        assertEquals("004", String.valueOf(h2.get(3).get("version")));
        assertEquals("005", String.valueOf(h2.get(4).get("version")));
        assertEquals("006", String.valueOf(h2.get(5).get("version")));
        assertEquals("007", String.valueOf(h2.get(6).get("version")));
        assertEquals(Boolean.TRUE, h2.get(0).get("success"));
        assertEquals(Boolean.TRUE, h2.get(1).get("success"));
        assertEquals(Boolean.TRUE, h2.get(2).get("success"));
        assertEquals(Boolean.TRUE, h2.get(3).get("success"));
        assertEquals(Boolean.TRUE, h2.get(4).get("success"));
        assertEquals(Boolean.TRUE, h2.get(5).get("success"));
        assertEquals(Boolean.TRUE, h2.get(6).get("success"));

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

        // (11d) knowledge_category now exists (V006 effect, BUSINESS-003).
        Integer categoryAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_category'",
                Integer.class);
        assertEquals(1, categoryAfterUpgrade,
                "knowledge_category must exist after upgrade to latest");

        // (11e) knowledge_point now exists (V007 effect, BUSINESS-003).
        Integer pointAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point'",
                Integer.class);
        assertEquals(1, pointAfterUpgrade,
                "knowledge_point must exist after upgrade to latest");

        // (12) Old data still exists with V002's note column NULL.
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT id, name, note FROM flyway_spike_record WHERE id = ?", preId);
        assertEquals("V001升级前保留数据", old.get("name"));

        // (13) V002..V007 idempotent on second migrate.
        MigrateResult result3 = flyway().migrate();
        assertEquals(0, result3.migrationsExecuted,
                "second migrate on already-latest schema must be a no-op");

        // (14) History still 7 rows.
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        assertEquals(7, finalCount);
    }

    // ==================== TEST C ====================

    @Test
    void latestDatabaseRequiresNoMigrationOnSecondMigrate() {
        // (1) Migrate to latest. V007 is the new latest version.
        MigrateResult first = flyway().migrate();
        assertEquals(7, first.migrationsExecuted,
                "first migrate must apply V001..V007 (actual: " + first.migrationsExecuted + ")");

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

        // (2e) Also confirm the V006 knowledge_category table exists.
        Integer categoryExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_category'",
                Integer.class);
        assertEquals(1, categoryExists,
                "knowledge_category must exist after fresh migrate to latest");

        // (2f) Also confirm the V007 knowledge_point table exists.
        Integer pointExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point'",
                Integer.class);
        assertEquals(1, pointExists,
                "knowledge_point must exist after fresh migrate to latest");

        // (3) Migrate AGAIN. Must be a no-op.
        MigrateResult second = flyway().migrate();
        assertEquals(0, second.migrationsExecuted,
                "second migrate must execute 0 migrations (actual: " + second.migrationsExecuted + ")");

        // (4) History still exactly 7 rows.
        Integer historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        assertEquals(7, historyCount);

        // (5) Versions unchanged.
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        List<String> expected = new ArrayList<>();
        expected.add("001");
        expected.add("002");
        expected.add("003");
        expected.add("004");
        expected.add("005");
        expected.add("006");
        expected.add("007");
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
