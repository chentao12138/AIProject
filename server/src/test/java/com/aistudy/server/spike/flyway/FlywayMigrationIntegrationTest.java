package com.aistudy.server.spike.flyway;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
 *   5. target latest -> migrate() -> V002..V012 applied
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
 *  14. verify production source_asset table (V008, BUSINESS-004):
 *      columns, indexes, unique storage_key, FKs, charset
 *  15. migrate again -> migrationsExecuted == 0
 *  16. history still V001..V012 only
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
@ResourceLock("aistudy-flyway-test")
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

        // (1) Migrate to latest. Expected version list is derived from
        // the real classpath (no hardcoded counts — grows with each
        // business migration).
        MigrateResult result = flyway().migrate();
        int applied = result.migrationsExecuted;
        List<String> expectedVersions = expectedMigrationVersions();
        assertEquals(expectedVersions.size(), applied,
                "Fresh migration must apply V001..V" + expectedVersions.get(expectedVersions.size() - 1)
                        + " (actual: " + applied + ")");

        // (2) Verify flyway_schema_history rows.
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history",
                Integer.class);
        assertEquals(expectedVersions.size(), finalCount);

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT installed_rank, version, description, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(expectedVersions.size(), history.size(),
                "history row count must match expected versions");
        for (int i = 0; i < expectedVersions.size(); i++) {
            assertEquals(expectedVersions.get(i), String.valueOf(history.get(i).get("version")));
            assertEquals(i + 1, history.get(i).get("installed_rank"));
            assertEquals(Boolean.TRUE, history.get(i).get("success"));
        }

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

        // (21) Verify the V008 source_asset table (BUSINESS-004).
        Integer assetTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_asset'",
                Integer.class);
        assertEquals(1, assetTableCount,
                "V008 must create the source_asset table");

        List<String> expectedAssetColumns = new ArrayList<>();
        expectedAssetColumns.add("id");
        expectedAssetColumns.add("space_id");
        expectedAssetColumns.add("source_id");
        expectedAssetColumns.add("asset_role");
        expectedAssetColumns.add("original_name");
        expectedAssetColumns.add("original_relative_path");
        expectedAssetColumns.add("storage_key");
        expectedAssetColumns.add("mime_type");
        expectedAssetColumns.add("size_bytes");
        expectedAssetColumns.add("sha256");
        expectedAssetColumns.add("created_at");
        List<String> actualAssetColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_asset' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedAssetColumns, actualAssetColumns,
                "source_asset must have exactly the expected columns in order");

        // (22) Verify source_asset indexes: list index, sha256 index,
        // and the UNIQUE storage_key.
        List<String> assetSourceCreatedIndex = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_asset' "
                        + "AND INDEX_NAME = 'idx_source_asset_space_source_created' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        List<String> expectedAssetSourceCreated = new ArrayList<>();
        expectedAssetSourceCreated.add("space_id");
        expectedAssetSourceCreated.add("source_id");
        expectedAssetSourceCreated.add("created_at");
        expectedAssetSourceCreated.add("id");
        assertEquals(expectedAssetSourceCreated, assetSourceCreatedIndex,
                "idx_source_asset_space_source_created must cover (space_id, source_id, created_at, id)");

        List<String> assetSha256Index = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_asset' "
                        + "AND INDEX_NAME = 'idx_source_asset_space_sha256' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        List<String> expectedAssetSha256 = new ArrayList<>();
        expectedAssetSha256.add("space_id");
        expectedAssetSha256.add("sha256");
        assertEquals(expectedAssetSha256, assetSha256Index,
                "idx_source_asset_space_sha256 must cover (space_id, sha256)");

        List<String> assetStorageKeyUnique = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_asset' "
                        + "AND INDEX_NAME = 'uk_source_asset_storage_key' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        List<String> expectedAssetStorageKey = new ArrayList<>();
        expectedAssetStorageKey.add("storage_key");
        assertEquals(expectedAssetStorageKey, assetStorageKeyUnique,
                "uk_source_asset_storage_key must be a unique index on storage_key");
        Map<String, Object> ukNonUnique = jdbc.queryForMap(
                "SELECT NON_UNIQUE FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_asset' "
                        + "AND INDEX_NAME = 'uk_source_asset_storage_key' LIMIT 1");
        assertEquals(0, ((Number) ukNonUnique.get("NON_UNIQUE")).intValue(),
                "uk_source_asset_storage_key must be UNIQUE");

        // (23) Verify source_asset FKs: space_id -> learning_space,
        // source_id -> source. Map by CONSTRAINT_NAME — the assertions
        // do NOT depend on information_schema return order.
        List<Map<String, Object>> assetFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'source_asset' "
                        + "  AND CONSTRAINT_NAME IN ('fk_source_asset_space', 'fk_source_asset_source')");
        assertEquals(2, assetFkRows.size(),
                "source_asset must have exactly 2 FKs (actual: " + assetFkRows.size() + ")");

        Map<String, Map<String, Object>> assetFkByName = new HashMap<>();
        for (Map<String, Object> row : assetFkRows) {
            assetFkByName.put(String.valueOf(row.get("CONSTRAINT_NAME")), row);
        }
        assertEquals("learning_space",
                assetFkByName.get("fk_source_asset_space").get("REFERENCED_TABLE_NAME"),
                "fk_source_asset_space must reference learning_space");
        assertEquals("id",
                assetFkByName.get("fk_source_asset_space").get("REFERENCED_COLUMN_NAME"),
                "fk_source_asset_space must reference learning_space(id)");
        assertEquals("source",
                assetFkByName.get("fk_source_asset_source").get("REFERENCED_TABLE_NAME"),
                "fk_source_asset_source must reference source");
        assertEquals("id",
                assetFkByName.get("fk_source_asset_source").get("REFERENCED_COLUMN_NAME"),
                "fk_source_asset_source must reference source(id)");

        // (24) Verify source_asset charset/collation (utf8mb4 / utf8mb4_unicode_ci).
        Map<String, Object> assetCharset = jdbc.queryForMap(
                "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                        + "FROM information_schema.TABLES T "
                        + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                        + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                        + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = 'source_asset'");
        assertEquals("utf8mb4", assetCharset.get("CHARACTER_SET_NAME"),
                "source_asset charset must be utf8mb4");
        assertEquals("utf8mb4_unicode_ci", assetCharset.get("COLLATION_NAME"),
                "source_asset collation must be utf8mb4_unicode_ci");

        // (25) Verify the V009 ingestion_job table (BUSINESS-005).
        Integer jobTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ingestion_job'",
                Integer.class);
        assertEquals(1, jobTableCount,
                "V009 must create the ingestion_job table");

        // (26) Verify ingestion_job columns in order.
        List<String> jobColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ingestion_job' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        List<String> expectedJobColumns = new ArrayList<>(List.of(
                "id", "space_id", "source_id", "asset_id", "status", "stage",
                "progress_percent", "started_at", "finished_at", "retry_count",
                "error_code", "error_message", "created_by_user_id",
                "created_at", "updated_at"));
        assertEquals(expectedJobColumns, jobColumns,
                "ingestion_job must have exactly the expected columns in order");

        // (27) Verify ingestion_job indexes: source history, status
        // lookup, asset lookup.
        List<String> jobIndexColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ingestion_job' "
                        + "AND INDEX_NAME = 'idx_ingestion_job_space_source_created' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("space_id", "source_id", "created_at", "id"),
                jobIndexColumns,
                "idx_ingestion_job_space_source_created must cover (space_id, source_id, created_at, id)");

        List<String> jobStatusIndexColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ingestion_job' "
                        + "AND INDEX_NAME = 'idx_ingestion_job_space_status_created' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("space_id", "status", "created_at", "id"),
                jobStatusIndexColumns,
                "idx_ingestion_job_space_status_created must cover (space_id, status, created_at, id)");

        List<String> jobAssetIndexColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ingestion_job' "
                        + "AND INDEX_NAME = 'idx_ingestion_job_space_asset' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("space_id", "asset_id"),
                jobAssetIndexColumns,
                "idx_ingestion_job_space_asset must cover (space_id, asset_id)");

        // (28) Verify ingestion_job FKs: space_id -> learning_space,
        // source_id -> source, asset_id -> source_asset. All default
        // RESTRICT (no CASCADE, matching the FK-by-name convention).
        List<Map<String, Object>> jobFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'ingestion_job' "
                        + "  AND CONSTRAINT_NAME IN ('fk_ingestion_job_space', "
                        + "    'fk_ingestion_job_source', 'fk_ingestion_job_asset')");
        assertEquals(3, jobFkRows.size(),
                "ingestion_job must have exactly 3 FKs (actual: " + jobFkRows.size() + ")");
        Map<String, Map<String, Object>> jobFkByName = new java.util.HashMap<>();
        for (Map<String, Object> row : jobFkRows) {
            jobFkByName.put((String) row.get("CONSTRAINT_NAME"), row);
        }
        assertEquals("learning_space",
                jobFkByName.get("fk_ingestion_job_space").get("REFERENCED_TABLE_NAME"),
                "fk_ingestion_job_space must reference learning_space");
        assertEquals("id",
                jobFkByName.get("fk_ingestion_job_space").get("REFERENCED_COLUMN_NAME"),
                "fk_ingestion_job_space must reference learning_space(id)");
        assertEquals("source",
                jobFkByName.get("fk_ingestion_job_source").get("REFERENCED_TABLE_NAME"),
                "fk_ingestion_job_source must reference source");
        assertEquals("id",
                jobFkByName.get("fk_ingestion_job_source").get("REFERENCED_COLUMN_NAME"),
                "fk_ingestion_job_source must reference source(id)");
        assertEquals("source_asset",
                jobFkByName.get("fk_ingestion_job_asset").get("REFERENCED_TABLE_NAME"),
                "fk_ingestion_job_asset must reference source_asset");
        assertEquals("id",
                jobFkByName.get("fk_ingestion_job_asset").get("REFERENCED_COLUMN_NAME"),
                "fk_ingestion_job_asset must reference source_asset(id)");

        // (29) Verify ingestion_job charset/collation (utf8mb4 / utf8mb4_unicode_ci).
        Map<String, Object> jobCharset = jdbc.queryForMap(
                "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                        + "FROM information_schema.TABLES T "
                        + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                        + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                        + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = 'ingestion_job'");
        assertEquals("utf8mb4", jobCharset.get("CHARACTER_SET_NAME"),
                "ingestion_job charset must be utf8mb4");
        assertEquals("utf8mb4_unicode_ci", jobCharset.get("COLLATION_NAME"),
                "ingestion_job collation must be utf8mb4_unicode_ci");

        // (30) Verify the V010 source_page table (BUSINESS-006).
        Integer pageTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_page'",
                Integer.class);
        assertEquals(1, pageTableCount,
                "V010 must create the source_page table");

        List<String> pageColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_page' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(List.of(
                        "id", "space_id", "source_id", "source_asset_id",
                        "source_page_number", "page_order", "printed_page_number",
                        "page_type", "order_confidence", "order_status",
                        "extracted_text", "extraction_confidence",
                        "created_at", "updated_at"),
                pageColumns,
                "source_page must have exactly the expected columns in order");

        // (30b) extracted_text MUST be LONGTEXT: the V1 text ingestion
        // limit is 64MB and 1 asset = 1 page, so the full decoded text
        // of a page can vastly exceed TEXT (~64KB) capacity
        // (AUTORUN-4H-PRE-RUNTIME-FIX-01).
        String extractedTextType = jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_page' "
                        + "AND COLUMN_NAME = 'extracted_text'",
                String.class);
        assertEquals("longtext", extractedTextType,
                "source_page.extracted_text must be LONGTEXT (64MB ingestion envelope)");
        String pageOrderType = jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_page' "
                        + "AND COLUMN_NAME = 'page_order'",
                String.class);
        assertEquals("int", pageOrderType,
                "source_page.page_order must remain INT");

        List<String> pageIndexColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_page' "
                        + "AND INDEX_NAME = 'idx_source_page_space_source_order' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("space_id", "source_id", "page_order", "id"),
                pageIndexColumns,
                "idx_source_page_space_source_order must cover (space_id, source_id, page_order, id)");

        List<Map<String, Object>> pageFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_page' "
                        + "  AND CONSTRAINT_NAME IN ('fk_source_page_space', 'fk_source_page_source', "
                        + "    'fk_source_page_asset')");
        assertEquals(3, pageFkRows.size(),
                "source_page must have exactly 3 FKs (actual: " + pageFkRows.size() + ")");
        Map<String, String> pageFkTables = new java.util.HashMap<>();
        for (Map<String, Object> row : pageFkRows) {
            pageFkTables.put((String) row.get("CONSTRAINT_NAME"),
                    (String) row.get("REFERENCED_TABLE_NAME"));
        }
        assertEquals("learning_space", pageFkTables.get("fk_source_page_space"));
        assertEquals("source", pageFkTables.get("fk_source_page_source"));
        assertEquals("source_asset", pageFkTables.get("fk_source_page_asset"));

        Map<String, Object> pageCharset = jdbc.queryForMap(
                "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                        + "FROM information_schema.TABLES T "
                        + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                        + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                        + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = 'source_page'");
        assertEquals("utf8mb4", pageCharset.get("CHARACTER_SET_NAME"),
                "source_page charset must be utf8mb4");
        assertEquals("utf8mb4_unicode_ci", pageCharset.get("COLLATION_NAME"),
                "source_page collation must be utf8mb4_unicode_ci");

        // (31) Verify the V011 content_block table (BUSINESS-006).
        Integer blockTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_block'",
                Integer.class);
        assertEquals(1, blockTableCount,
                "V011 must create the content_block table");

        List<String> blockColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_block' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(List.of(
                        "id", "space_id", "source_id", "source_page_id",
                        "source_outline_node_id", "block_type", "sort_order",
                        "normalized_text", "structured_data_json", "locator_json",
                        "created_at", "updated_at"),
                blockColumns,
                "content_block must have exactly the expected columns in order");

        // (31b) normalized_text stays TEXT (NOT LONGTEXT): blocks are
        // deliberately small for provenance citation; the parser enforces
        // MAX_BLOCK_UTF8_BYTES (60,000 UTF-8 bytes) per block
        // (AUTORUN-4H-PRE-RUNTIME-FIX-01).
        String normalizedTextType = jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_block' "
                        + "AND COLUMN_NAME = 'normalized_text'",
                String.class);
        assertEquals("text", normalizedTextType,
                "content_block.normalized_text must remain TEXT (60KB UTF-8 bounded blocks)");

        List<String> blockIndexColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_block' "
                        + "AND INDEX_NAME = 'idx_content_block_space_source_order' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("space_id", "source_id", "sort_order", "id"),
                blockIndexColumns,
                "idx_content_block_space_source_order must cover (space_id, source_id, sort_order, id)");

        List<Map<String, Object>> blockFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_block' "
                        + "  AND CONSTRAINT_NAME IN ('fk_content_block_space', 'fk_content_block_source', "
                        + "    'fk_content_block_page')");
        assertEquals(3, blockFkRows.size(),
                "content_block must have exactly 3 FKs (actual: " + blockFkRows.size() + ")");
        Map<String, String> blockFkTables = new java.util.HashMap<>();
        for (Map<String, Object> row : blockFkRows) {
            blockFkTables.put((String) row.get("CONSTRAINT_NAME"),
                    (String) row.get("REFERENCED_TABLE_NAME"));
        }
        assertEquals("learning_space", blockFkTables.get("fk_content_block_space"));
        assertEquals("source", blockFkTables.get("fk_content_block_source"));
        assertEquals("source_page", blockFkTables.get("fk_content_block_page"));

        Map<String, Object> blockCharset = jdbc.queryForMap(
                "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                        + "FROM information_schema.TABLES T "
                        + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                        + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                        + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = 'content_block'");
        assertEquals("utf8mb4", blockCharset.get("CHARACTER_SET_NAME"),
                "content_block charset must be utf8mb4");
        assertEquals("utf8mb4_unicode_ci", blockCharset.get("COLLATION_NAME"),
                "content_block collation must be utf8mb4_unicode_ci");

        // (32) Verify the V012 knowledge_point_source table (BUSINESS-007).
        Integer kpsTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source'",
                Integer.class);
        assertEquals(1, kpsTableCount,
                "V012 must create the knowledge_point_source table");

        List<String> kpsColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(List.of(
                        "id", "space_id", "knowledge_point_id", "content_block_id",
                        "relation_type", "relevance_score", "created_by_user_id",
                        "created_at"),
                kpsColumns,
                "knowledge_point_source must have exactly the expected columns in order");

        // (33) Verify the pair unique key and both lookup indexes.
        List<String> pairUkColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source' "
                        + "AND INDEX_NAME = 'uk_knowledge_point_source_pair' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("knowledge_point_id", "content_block_id"),
                pairUkColumns,
                "uk_knowledge_point_source_pair must cover (knowledge_point_id, content_block_id)");
        Integer pairNonUnique = jdbc.queryForObject(
                "SELECT NON_UNIQUE FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source' "
                        + "AND INDEX_NAME = 'uk_knowledge_point_source_pair' LIMIT 1",
                Integer.class);
        assertEquals(0, pairNonUnique, "uk_knowledge_point_source_pair must be UNIQUE");

        List<String> kpsPointIndex = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source' "
                        + "AND INDEX_NAME = 'idx_knowledge_point_source_space_point' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("space_id", "knowledge_point_id", "id"),
                kpsPointIndex,
                "idx_knowledge_point_source_space_point must cover (space_id, knowledge_point_id, id)");

        List<String> kpsBlockIndex = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source' "
                        + "AND INDEX_NAME = 'idx_knowledge_point_source_space_block' "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("space_id", "content_block_id", "id"),
                kpsBlockIndex,
                "idx_knowledge_point_source_space_block must cover (space_id, content_block_id, id)");

        // (34) Verify knowledge_point_source FKs: space / point / block.
        List<Map<String, Object>> kpsFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source' "
                        + "  AND CONSTRAINT_NAME IN ('fk_knowledge_point_source_space', "
                        + "    'fk_knowledge_point_source_point', 'fk_knowledge_point_source_block')");
        assertEquals(3, kpsFkRows.size(),
                "knowledge_point_source must have exactly 3 FKs (actual: " + kpsFkRows.size() + ")");
        Map<String, String> kpsFkTables = new java.util.HashMap<>();
        for (Map<String, Object> row : kpsFkRows) {
            kpsFkTables.put((String) row.get("CONSTRAINT_NAME"),
                    (String) row.get("REFERENCED_TABLE_NAME"));
        }
        assertEquals("learning_space", kpsFkTables.get("fk_knowledge_point_source_space"));
        assertEquals("knowledge_point", kpsFkTables.get("fk_knowledge_point_source_point"));
        assertEquals("content_block", kpsFkTables.get("fk_knowledge_point_source_block"));

        Map<String, Object> kpsCharset = jdbc.queryForMap(
                "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                        + "FROM information_schema.TABLES T "
                        + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                        + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                        + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = 'knowledge_point_source'");
        assertEquals("utf8mb4", kpsCharset.get("CHARACTER_SET_NAME"),
                "knowledge_point_source charset must be utf8mb4");
        assertEquals("utf8mb4_unicode_ci", kpsCharset.get("COLLATION_NAME"),
                "knowledge_point_source collation must be utf8mb4_unicode_ci");

        // (35) Verify the V019 mastery table (BUSINESS-014): columns,
        // unique (user_subject, space_id, knowledge_point_id), FKs to
        // learning_space + knowledge_point, charset.
        Integer masteryTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mastery'",
                Integer.class);
        assertEquals(1, masteryTableCount,
                "V019 must create the mastery table");

        List<String> expectedMasteryColumns = new ArrayList<>();
        expectedMasteryColumns.add("id");
        expectedMasteryColumns.add("user_subject");
        expectedMasteryColumns.add("space_id");
        expectedMasteryColumns.add("knowledge_point_id");
        expectedMasteryColumns.add("mastery_score");
        expectedMasteryColumns.add("confidence");
        expectedMasteryColumns.add("practice_evidence_count");
        expectedMasteryColumns.add("exam_evidence_count");
        expectedMasteryColumns.add("review_evidence_count");
        expectedMasteryColumns.add("last_evidence_at");
        expectedMasteryColumns.add("created_at");
        expectedMasteryColumns.add("updated_at");
        List<String> actualMasteryColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mastery' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedMasteryColumns, actualMasteryColumns,
                "mastery must have exactly the expected columns in order");

        List<String> masteryUkColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mastery' "
                        + "AND INDEX_NAME = 'uk_mastery_user_space_kp' ORDER BY SEQ_IN_INDEX",
                String.class);
        List<String> expectedMasteryUk = new ArrayList<>();
        expectedMasteryUk.add("user_subject");
        expectedMasteryUk.add("space_id");
        expectedMasteryUk.add("knowledge_point_id");
        assertEquals(expectedMasteryUk, masteryUkColumns,
                "uk_mastery_user_space_kp must cover (user_subject, space_id, knowledge_point_id)");
        Integer masteryUkNonUnique = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mastery' "
                        + "AND INDEX_NAME = 'uk_mastery_user_space_kp' AND NON_UNIQUE = 0",
                Integer.class);
        assertTrue(masteryUkNonUnique > 0, "uk_mastery_user_space_kp must be a UNIQUE index");

        List<Map<String, Object>> masteryFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'mastery' "
                        + "  AND CONSTRAINT_NAME IN ('fk_mastery_space', 'fk_mastery_kp')");
        assertEquals(2, masteryFkRows.size(),
                "mastery must have exactly 2 FKs (actual: " + masteryFkRows.size() + ")");
        Map<String, Map<String, Object>> masteryFkByName = new HashMap<>();
        for (Map<String, Object> row : masteryFkRows) {
            masteryFkByName.put(String.valueOf(row.get("CONSTRAINT_NAME")), row);
        }
        assertEquals("learning_space",
                masteryFkByName.get("fk_mastery_space").get("REFERENCED_TABLE_NAME"),
                "fk_mastery_space must reference learning_space");
        assertEquals("id",
                masteryFkByName.get("fk_mastery_space").get("REFERENCED_COLUMN_NAME"),
                "fk_mastery_space must reference learning_space(id)");
        assertEquals("knowledge_point",
                masteryFkByName.get("fk_mastery_kp").get("REFERENCED_TABLE_NAME"),
                "fk_mastery_kp must reference knowledge_point");
        assertEquals("id",
                masteryFkByName.get("fk_mastery_kp").get("REFERENCED_COLUMN_NAME"),
                "fk_mastery_kp must reference knowledge_point(id)");

        Map<String, Object> masteryCharset = jdbc.queryForMap(
                "SELECT CCSA.CHARACTER_SET_NAME, CCSA.COLLATION_NAME "
                        + "FROM information_schema.TABLES T "
                        + "JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA "
                        + "  ON T.TABLE_COLLATION = CCSA.COLLATION_NAME "
                        + "WHERE T.TABLE_SCHEMA = DATABASE() AND T.TABLE_NAME = 'mastery'");
        assertEquals("utf8mb4", masteryCharset.get("CHARACTER_SET_NAME"),
                "mastery charset must be utf8mb4");
        assertEquals("utf8mb4_unicode_ci", masteryCharset.get("COLLATION_NAME"),
                "mastery collation must be utf8mb4_unicode_ci");

        // (36) Verify the V020 exam_diagnosis + exam_diagnosis_item
        // tables (BUSINESS-015): unique per attempt, FK to
        // exam_attempt, item FK to exam_diagnosis.
        Integer diagnosisTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_diagnosis'",
                Integer.class);
        assertEquals(1, diagnosisTableCount,
                "V020 must create the exam_diagnosis table");

        List<String> expectedDiagnosisColumns = new ArrayList<>();
        expectedDiagnosisColumns.add("id");
        expectedDiagnosisColumns.add("exam_attempt_id");
        expectedDiagnosisColumns.add("user_subject");
        expectedDiagnosisColumns.add("space_id");
        expectedDiagnosisColumns.add("summary");
        expectedDiagnosisColumns.add("created_at");
        List<String> actualDiagnosisColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_diagnosis' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedDiagnosisColumns, actualDiagnosisColumns,
                "exam_diagnosis must have exactly the expected columns in order");

        List<String> diagnosisUkColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_diagnosis' "
                        + "AND INDEX_NAME = 'uk_exam_diagnosis_attempt' ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("exam_attempt_id"), diagnosisUkColumns,
                "uk_exam_diagnosis_attempt must cover exam_attempt_id");
        Integer diagnosisUkNonUnique = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_diagnosis' "
                        + "AND INDEX_NAME = 'uk_exam_diagnosis_attempt' AND NON_UNIQUE = 0",
                Integer.class);
        assertTrue(diagnosisUkNonUnique > 0, "uk_exam_diagnosis_attempt must be UNIQUE");

        List<Map<String, Object>> diagnosisFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'exam_diagnosis' "
                        + "  AND CONSTRAINT_NAME IN ('fk_exam_diagnosis_space', 'fk_exam_diagnosis_attempt')");
        assertEquals(2, diagnosisFkRows.size(),
                "exam_diagnosis must have exactly 2 FKs (actual: " + diagnosisFkRows.size() + ")");
        Map<String, String> diagnosisFkTables = new HashMap<>();
        for (Map<String, Object> row : diagnosisFkRows) {
            diagnosisFkTables.put(String.valueOf(row.get("CONSTRAINT_NAME")),
                    String.valueOf(row.get("REFERENCED_TABLE_NAME")));
        }
        assertEquals("learning_space", diagnosisFkTables.get("fk_exam_diagnosis_space"),
                "fk_exam_diagnosis_space must reference learning_space");
        assertEquals("exam_attempt", diagnosisFkTables.get("fk_exam_diagnosis_attempt"),
                "fk_exam_diagnosis_attempt must reference exam_attempt");

        Integer diagnosisItemTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_diagnosis_item'",
                Integer.class);
        assertEquals(1, diagnosisItemTableCount,
                "V020 must create the exam_diagnosis_item table");

        List<String> expectedDiagnosisItemColumns = new ArrayList<>();
        expectedDiagnosisItemColumns.add("id");
        expectedDiagnosisItemColumns.add("exam_diagnosis_id");
        expectedDiagnosisItemColumns.add("dimension_type");
        expectedDiagnosisItemColumns.add("dimension_id");
        expectedDiagnosisItemColumns.add("label");
        expectedDiagnosisItemColumns.add("score");
        expectedDiagnosisItemColumns.add("max_score");
        expectedDiagnosisItemColumns.add("accuracy");
        expectedDiagnosisItemColumns.add("evidence_count");
        expectedDiagnosisItemColumns.add("severity");
        expectedDiagnosisItemColumns.add("recommendation");
        expectedDiagnosisItemColumns.add("created_at");
        List<String> actualDiagnosisItemColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_diagnosis_item' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedDiagnosisItemColumns, actualDiagnosisItemColumns,
                "exam_diagnosis_item must have exactly the expected columns in order");

        List<Map<String, Object>> itemFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'exam_diagnosis_item' "
                        + "  AND CONSTRAINT_NAME = 'fk_diagnosis_item_diagnosis'");
        assertEquals(1, itemFkRows.size(),
                "exam_diagnosis_item must have exactly 1 FK");
        assertEquals("exam_diagnosis",
                String.valueOf(itemFkRows.get(0).get("REFERENCED_TABLE_NAME")),
                "fk_diagnosis_item_diagnosis must reference exam_diagnosis");

        // (37) Verify the V021 study_plan + study_task tables
        // (BUSINESS-016): plan FK to learning_space, task FKs to
        // study_plan + learning_space, task index on plan.
        Integer studyPlanTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'study_plan'",
                Integer.class);
        assertEquals(1, studyPlanTableCount,
                "V021 must create the study_plan table");

        List<String> expectedStudyPlanColumns = new ArrayList<>();
        expectedStudyPlanColumns.add("id");
        expectedStudyPlanColumns.add("user_subject");
        expectedStudyPlanColumns.add("space_id");
        expectedStudyPlanColumns.add("name");
        expectedStudyPlanColumns.add("start_date");
        expectedStudyPlanColumns.add("end_date");
        expectedStudyPlanColumns.add("status");
        expectedStudyPlanColumns.add("created_at");
        expectedStudyPlanColumns.add("updated_at");
        List<String> actualStudyPlanColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'study_plan' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedStudyPlanColumns, actualStudyPlanColumns,
                "study_plan must have exactly the expected columns in order");

        List<Map<String, Object>> studyPlanFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'study_plan' "
                        + "  AND CONSTRAINT_NAME = 'fk_study_plan_space'");
        assertEquals(1, studyPlanFkRows.size(),
                "study_plan must have exactly 1 FK");
        assertEquals("learning_space",
                String.valueOf(studyPlanFkRows.get(0).get("REFERENCED_TABLE_NAME")),
                "fk_study_plan_space must reference learning_space");

        Integer studyTaskTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'study_task'",
                Integer.class);
        assertEquals(1, studyTaskTableCount,
                "V021 must create the study_task table");

        List<String> expectedStudyTaskColumns = new ArrayList<>();
        expectedStudyTaskColumns.add("id");
        expectedStudyTaskColumns.add("study_plan_id");
        expectedStudyTaskColumns.add("user_subject");
        expectedStudyTaskColumns.add("space_id");
        expectedStudyTaskColumns.add("task_type");
        expectedStudyTaskColumns.add("target_type");
        expectedStudyTaskColumns.add("target_id");
        expectedStudyTaskColumns.add("title");
        expectedStudyTaskColumns.add("reason");
        expectedStudyTaskColumns.add("due_at");
        expectedStudyTaskColumns.add("priority");
        expectedStudyTaskColumns.add("status");
        expectedStudyTaskColumns.add("completed_at");
        expectedStudyTaskColumns.add("created_at");
        expectedStudyTaskColumns.add("updated_at");
        List<String> actualStudyTaskColumns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'study_task' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
        assertEquals(expectedStudyTaskColumns, actualStudyTaskColumns,
                "study_task must have exactly the expected columns in order");

        List<String> studyTaskPlanIndex = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'study_task' "
                        + "AND INDEX_NAME = 'idx_study_task_plan' ORDER BY SEQ_IN_INDEX",
                String.class);
        assertEquals(List.of("study_plan_id"), studyTaskPlanIndex,
                "idx_study_task_plan must cover study_plan_id");

        List<Map<String, Object>> studyTaskFkRows = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME "
                        + "FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'study_task' "
                        + "  AND CONSTRAINT_NAME IN ('fk_study_task_plan', 'fk_study_task_space')");
        assertEquals(2, studyTaskFkRows.size(),
                "study_task must have exactly 2 FKs");
        Map<String, String> studyTaskFkTables = new HashMap<>();
        for (Map<String, Object> row : studyTaskFkRows) {
            studyTaskFkTables.put(String.valueOf(row.get("CONSTRAINT_NAME")),
                    String.valueOf(row.get("REFERENCED_TABLE_NAME")));
        }
        assertEquals("study_plan", studyTaskFkTables.get("fk_study_task_plan"),
                "fk_study_task_plan must reference study_plan");
        assertEquals("learning_space", studyTaskFkTables.get("fk_study_task_space"),
                "fk_study_task_space must reference learning_space");
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

        // (7) Migrate to latest. All migrations after V001 apply.
        MigrateResult result2 = flyway().migrate();
        int appliedV2 = result2.migrationsExecuted;
        List<String> expectedVersions = expectedMigrationVersions();
        assertEquals(expectedVersions.size() - 1, appliedV2,
                "target=latest on V001-only db must apply V002..V"
                        + expectedVersions.get(expectedVersions.size() - 1)
                        + " (actual: " + appliedV2 + ")");

        // (8) Verify history has one row per migration: V001..latest.
        List<Map<String, Object>> h2 = jdbc.queryForList(
                "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(expectedVersions.size(), h2.size());
        for (int i = 0; i < expectedVersions.size(); i++) {
            assertEquals(expectedVersions.get(i), String.valueOf(h2.get(i).get("version")));
            assertEquals(Boolean.TRUE, h2.get(i).get("success"));
        }

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

        // (11f) source_asset now exists (V008 effect, BUSINESS-004).
        Integer assetAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_asset'",
                Integer.class);
        assertEquals(1, assetAfterUpgrade,
                "source_asset must exist after upgrade to latest");

        // (11g) ingestion_job now exists (V009 effect, BUSINESS-005).
        Integer jobAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ingestion_job'",
                Integer.class);
        assertEquals(1, jobAfterUpgrade,
                "ingestion_job must exist after upgrade to latest");

        // (11h) source_page now exists (V010 effect, BUSINESS-006).
        Integer pageAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_page'",
                Integer.class);
        assertEquals(1, pageAfterUpgrade,
                "source_page must exist after upgrade to latest");

        // (11i) content_block now exists (V011 effect, BUSINESS-006).
        Integer blockAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_block'",
                Integer.class);
        assertEquals(1, blockAfterUpgrade,
                "content_block must exist after upgrade to latest");

        // (11j) knowledge_point_source now exists (V012 effect, BUSINESS-007).
        Integer kpsAfterUpgrade = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source'",
                Integer.class);
        assertEquals(1, kpsAfterUpgrade,
                "knowledge_point_source must exist after upgrade to latest");

        // (12) Old data still exists with V002's note column NULL.
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT id, name, note FROM flyway_spike_record WHERE id = ?", preId);
        assertEquals("V001升级前保留数据", old.get("name"));

        // (13) second migrate on already-latest schema must be a no-op.
        MigrateResult result3 = flyway().migrate();
        assertEquals(0, result3.migrationsExecuted,
                "second migrate on already-latest schema must be a no-op");

        // (14) History still exactly one row per migration
        // (V001 + V002..latest; migrationsExecuted in (7) was the
        // delta count, not the history row count — RUNTIME-FIX-02-H).
        Integer finalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        assertEquals(expectedMigrationVersions().size(), finalCount);
    }

    // ==================== TEST C ====================

    @Test
    void latestDatabaseRequiresNoMigrationOnSecondMigrate() {
        // (1) Migrate to latest. Count derived from the real classpath.
        MigrateResult first = flyway().migrate();
        List<String> expectedVersions = expectedMigrationVersions();
        assertEquals(expectedVersions.size(), first.migrationsExecuted,
                "first migrate must apply V001..V"
                        + expectedVersions.get(expectedVersions.size() - 1)
                        + " (actual: " + first.migrationsExecuted + ")");

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

        // (2g) Also confirm the V008 source_asset table exists.
        Integer assetExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_asset'",
                Integer.class);
        assertEquals(1, assetExists,
                "source_asset must exist after fresh migrate to latest");

        // (2h) Also confirm the V009 ingestion_job table exists.
        Integer jobExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ingestion_job'",
                Integer.class);
        assertEquals(1, jobExists,
                "ingestion_job must exist after fresh migrate to latest");

        // (2i) Also confirm the V010 source_page table exists.
        Integer pageExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'source_page'",
                Integer.class);
        assertEquals(1, pageExists,
                "source_page must exist after fresh migrate to latest");

        // (2j) Also confirm the V011 content_block table exists.
        Integer blockExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'content_block'",
                Integer.class);
        assertEquals(1, blockExists,
                "content_block must exist after fresh migrate to latest");

        // (2k) Also confirm the V012 knowledge_point_source table exists.
        Integer kpsExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_point_source'",
                Integer.class);
        assertEquals(1, kpsExists,
                "knowledge_point_source must exist after fresh migrate to latest");

        // (3) Migrate AGAIN. Must be a no-op.
        MigrateResult second = flyway().migrate();
        assertEquals(0, second.migrationsExecuted,
                "second migrate must execute 0 migrations (actual: " + second.migrationsExecuted + ")");

        // (4) History still exactly one row per migration.
        Integer historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        assertEquals(expectedVersions.size(), historyCount);

        // (5) Versions unchanged.
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        assertEquals(expectedVersions, versions);
    }

    /**
     * Derives the expected migration version list from the real
     * classpath ({@code db/migration/V*.sql}), so this test never
     * needs a hardcoded count again when business migrations are
     * added (RUNTIME-FIX-02-H recurring staleness eliminated at
     * BUSINESS-008).
     */
    private List<String> expectedMigrationVersions() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*.sql");
            List<String> versions = new ArrayList<>();
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name != null && name.startsWith("V") && name.contains("__")) {
                    versions.add(name.substring(1, name.indexOf("__")));
                }
            }
            versions.sort(Comparator.comparingInt(Integer::parseInt));
            return versions;
        } catch (Exception e) {
            throw new IllegalStateException("cannot enumerate migration files", e);
        }
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
