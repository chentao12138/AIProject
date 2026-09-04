package com.aistudy.server.spike.mysql;

import com.aistudy.server.spike.mysql.mapper.SpikeRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPIKE-002 ONLY. Real MySQL integration test for MyBatis-Plus BaseMapper.
 * Requires a running MySQL 8.x reachable at DB_URL (see deploy/local/mysql/).
 */
@SpringBootTest
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpikeRecordMapperIntegrationTest {

    private static final String CHINESE_NAME = "数据库系统工程师";
    private static final String CHINESE_DESC = "MyBatis-Plus 与 MySQL 中文写入验证";

    @Autowired
    private SpikeRecordMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Long createdId;

    @AfterEach
    void cleanup() {
        // Clean up whatever this test method inserted (even if it failed).
        if (createdId != null) {
            mapper.deleteById(createdId);
            createdId = null;
        }
        // Also sweep any leftover SPIKE rows from previous aborted runs so the
        // table is empty after every test class run.
        jdbc.update("DELETE FROM spike_record");
    }

    @Test
    @Order(1)
    void insertSelectDeleteRoundTrip() {
        // INSERT via BaseMapper
        SpikeRecord record = new SpikeRecord();
        record.setName(CHINESE_NAME);
        record.setDescription(CHINESE_DESC);
        record.setCreatedAt(LocalDateTime.now());
        int inserted = mapper.insert(record);
        assertEquals(1, inserted, "BaseMapper.insert should return 1");
        assertNotNull(record.getId(), "insert should populate auto-increment id");
        assertTrue(record.getId() > 0, "generated id should be positive");
        createdId = record.getId();

        // SELECT via BaseMapper
        SpikeRecord loaded = mapper.selectById(record.getId());
        assertNotNull(loaded, "BaseMapper.selectById should find the row we inserted");
        assertEquals(CHINESE_NAME, loaded.getName(),
                "Chinese name must survive roundtrip exactly");
        assertEquals(CHINESE_DESC, loaded.getDescription(),
                "Chinese description must survive roundtrip exactly");
        assertNotNull(loaded.getCreatedAt(), "created_at must be populated");

        // Raw SQL: byte-level verification of UTF-8 storage.
        // MySQL HEX() is uppercase; uppercase both sides for a strict byte compare.
        String storedHexName = jdbc.queryForObject(
                "SELECT HEX(name) FROM spike_record WHERE id = ?",
                String.class, record.getId());
        String expectedHexName = HexFormat.of()
                .formatHex(CHINESE_NAME.getBytes(StandardCharsets.UTF_8))
                .toUpperCase();
        assertEquals(expectedHexName, storedHexName,
                "MySQL must store the exact UTF-8 byte sequence for Chinese text");

        String storedHexDesc = jdbc.queryForObject(
                "SELECT HEX(description) FROM spike_record WHERE id = ?",
                String.class, record.getId());
        String expectedHexDesc = HexFormat.of()
                .formatHex(CHINESE_DESC.getBytes(StandardCharsets.UTF_8))
                .toUpperCase();
        assertEquals(expectedHexDesc, storedHexDesc,
                "MySQL must store the exact UTF-8 byte sequence for Chinese description");

        // DELETE via BaseMapper
        int deleted = mapper.deleteById(record.getId());
        assertEquals(1, deleted, "BaseMapper.deleteById should return 1");
        createdId = null;

        // Confirm row is gone
        SpikeRecord afterDelete = mapper.selectById(record.getId());
        assertNull(afterDelete, "Row should no longer exist after deleteById");
    }

    @Test
    @Order(2)
    void connectionIsReallyMySql8AndCharsetIsUtf8mb4() {
        String productVersion = jdbc.queryForObject("SELECT @@version", String.class);
        assertNotNull(productVersion);
        assertTrue(productVersion.startsWith("8."),
                "MySQL version should be 8.x, got: " + productVersion);

        String dbCharset = jdbc.queryForObject("SELECT @@character_set_database", String.class);
        String dbCollation = jdbc.queryForObject("SELECT @@collation_database", String.class);
        assertEquals("utf8mb4", dbCharset, "database charset must be utf8mb4");
        assertTrue(dbCollation.startsWith("utf8mb4"),
                "database collation must be utf8mb4_*, got: " + dbCollation);

        // Table-level collation (avoids DEFAULT_CHARACTER_SET_NAME which is
        // not the right field for asserting table-level utf8mb4).
        String tableCollation = jdbc.queryForObject(
                "SELECT TABLE_COLLATION FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_record'",
                String.class);
        assertNotNull(tableCollation,
                "spike_record TABLE_COLLATION should be non-null");
        assertTrue(tableCollation.startsWith("utf8mb4"),
                "spike_record TABLE_COLLATION must be utf8mb4_*, got: " + tableCollation);

        // Column-level charset for name/description
        String columnNameCharset = jdbc.queryForObject(
                "SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_record' "
                        + "AND COLUMN_NAME = 'name'",
                String.class);
        assertEquals("utf8mb4", columnNameCharset,
                "spike_record.name column charset must be utf8mb4");

        String columnDescCharset = jdbc.queryForObject(
                "SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'spike_record' "
                        + "AND COLUMN_NAME = 'description'",
                String.class);
        assertEquals("utf8mb4", columnDescCharset,
                "spike_record.description column charset must be utf8mb4");
    }
}
