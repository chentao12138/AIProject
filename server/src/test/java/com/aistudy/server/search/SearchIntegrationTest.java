package com.aistudy.server.search;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-022 — Search vertical slice (SQL ranking, pagination, types,
 * LIKE escaping, Unicode).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String OWNER = "biz-search-user";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    private String token;
    private Long spaceId;

    @BeforeAll
    void guard() {
        assertSchema();
    }

    @AfterAll
    void noop() {
    }

    @BeforeEach
    void prepare() {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        clean();
        spaceId = insertSpace(OWNER, "搜索空间");
        token = jwtAccessTokenService.issueAccessToken(OWNER);
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    // ==================== result types ====================

    @Test
    void sourceTitleMatchReturnsSource() throws Exception {
        insertSource(spaceId, "数据库系统工程师教程");
        String body = search("数据库", null, 0, 20);
        assertTrue(body.contains("\"type\":\"SOURCE\""), body);
        assertTrue(body.contains("数据库系统工程师教程"), body);
    }

    @Test
    void contentBlockMatchReturnsBlock() throws Exception {
        Long sourceId = insertSource(spaceId, "教材");
        Long assetId = insertAsset(spaceId, sourceId, "a.txt");
        Long pageId = insertPage(spaceId, sourceId, assetId, 1, "全文");
        insertBlock(spaceId, sourceId, pageId, 0, "关系数据库规范化理论详解");
        String body = search("规范化", null, 0, 20);
        assertTrue(body.contains("\"type\":\"CONTENT_BLOCK\""), body);
    }

    @Test
    void knowledgePointTitleAndSummaryMatch() throws Exception {
        insertKnowledgePoint(spaceId, "事务隔离级别", "READ COMMITTED 说明");
        String body = search("事务隔离", null, 0, 20);
        assertTrue(body.contains("\"type\":\"KNOWLEDGE_POINT\""), body);
        String body2 = search("COMMITTED", null, 0, 20);
        assertTrue(body2.contains("\"type\":\"KNOWLEDGE_POINT\""), body2);
    }

    @Test
    void questionStemMatchReturnsQuestion() throws Exception {
        insertQuestion(spaceId, "什么是 ACID？", "{\"correctOptionKey\":\"A\"}");
        String body = search("ACID", null, 0, 20);
        assertTrue(body.contains("\"type\":\"QUESTION\""), body);
        assertTrue(body.contains("什么是 ACID"), body);
    }

    // ==================== ranking ====================

    @Test
    void sourceExactRanksBeforePrefixBeforeContains() throws Exception {
        insertSource(spaceId, "Java");
        insertSource(spaceId, "Java并发编程");
        insertSource(spaceId, "深入理解Java虚拟机");
        String body = search("Java", "SOURCE", 0, 20);
        int exact = body.indexOf("\"title\":\"Java\"");
        int prefix = body.indexOf("\"title\":\"Java并发编程\"");
        int contains = body.indexOf("\"title\":\"深入理解Java虚拟机\"");
        assertTrue(exact >= 0 && prefix >= 0 && contains >= 0, body);
        assertTrue(exact < prefix, "exact must rank before prefix: " + body);
        assertTrue(prefix < contains, "prefix must rank before contains: " + body);
    }

    @Test
    void knowledgePointTitleRanksBeforeSummaryMatch() throws Exception {
        insertKnowledgePoint(spaceId, "索引B+树", "无关内容");
        insertKnowledgePoint(spaceId, "其他主题", "这里提到索引B+树的细节");
        String body = search("索引B+树", "KNOWLEDGE_POINT", 0, 20);
        int titleHit = body.indexOf("\"title\":\"索引B+树\"");
        int summaryHit = body.indexOf("\"title\":\"其他主题\"");
        assertTrue(titleHit >= 0 && summaryHit >= 0, body);
        assertTrue(titleHit < summaryHit, "title match must rank above summary-only: " + body);
    }

    // ==================== pagination ====================

    @Test
    void globalPaginationIsDeterministicAndDisjoint() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insertSource(spaceId, "PageItem" + i + "Shared");
        }
        String p0 = search("PageItem", "SOURCE", 0, 2);
        String p1 = search("PageItem", "SOURCE", 1, 2);
        assertTrue(p0.contains("\"totalElements\":5"), p0);
        assertTrue(p0.contains("\"totalPages\":3"), p0);
        List<String> ids0 = extractIds(p0);
        List<String> ids1 = extractIds(p1);
        assertEquals(2, ids0.size(), p0);
        assertEquals(2, ids1.size(), p1);
        for (String id : ids0) {
            assertFalse(ids1.contains(id), "pages must not overlap: " + p0 + " / " + p1);
        }
        String p0again = search("PageItem", "SOURCE", 0, 2);
        assertEquals(ids0, extractIds(p0again), "repeat search must be stable");
    }

    // ==================== type filter ====================

    @Test
    void typeFilterSelectsOnlyRequestedTypes() throws Exception {
        insertSource(spaceId, "FilterAlphaSource");
        insertQuestion(spaceId, "FilterAlphaQuestion?", "{\"correctOptionKey\":\"A\"}");
        String onlySource = search("FilterAlpha", "SOURCE", 0, 20);
        assertTrue(onlySource.contains("SOURCE"), onlySource);
        assertFalse(onlySource.contains("QUESTION"), onlySource);
        String onlyQuestion = search("FilterAlpha", "QUESTION", 0, 20);
        assertTrue(onlyQuestion.contains("QUESTION"), onlyQuestion);
        assertFalse(onlyQuestion.contains("\"type\":\"SOURCE\""), onlyQuestion);
        String both = search("FilterAlpha", "SOURCE,QUESTION", 0, 20);
        assertTrue(both.contains("SOURCE") && both.contains("QUESTION"), both);
    }

    @Test
    void invalidTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceId)
                        .param("q", "x")
                        .param("types", "NOT_A_TYPE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ==================== validation ====================

    @Test
    void blankQueryReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceId)
                        .param("q", "   ")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overlongQueryReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceId)
                        .param("q", "a".repeat(201))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidPageOrSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceId)
                        .param("q", "x")
                        .param("page", "-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceId)
                        .param("q", "x")
                        .param("size", "0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceId)
                        .param("q", "x")
                        .param("size", "101")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ==================== LIKE escaping ====================

    @Test
    void percentAndUnderscoreAreLiteral() throws Exception {
        insertSource(spaceId, "100%完成");
        insertSource(spaceId, "100X完成");
        String body = search("100%", "SOURCE", 0, 20);
        assertTrue(body.contains("100%完成"), body);
        assertFalse(body.contains("100X完成"), "wildcard % must not match arbitrary chars: " + body);

        insertSource(spaceId, "a_b");
        insertSource(spaceId, "axb");
        String under = search("a_b", "SOURCE", 0, 20);
        assertTrue(under.contains("\"title\":\"a_b\""), under);
        assertFalse(under.contains("\"title\":\"axb\""), under);
    }

    // ==================== unicode ====================

    @Test
    void chineseSearchWorks() throws Exception {
        insertSource(spaceId, "数据库系统概论");
        insertKnowledgePoint(spaceId, "范式理论", "第一范式到BCNF");
        String body = search("数据库", null, 0, 20);
        assertTrue(body.contains("数据库系统概论"), body);
        String body2 = search("范式", null, 0, 20);
        assertTrue(body2.contains("范式理论"), body2);
        assertTrue(body2.contains("第一范式"), body2);
    }

    // ==================== helpers ====================

    private String search(String q, String types, int page, int size) throws Exception {
        var req = get("/api/v1/spaces/{spaceId}/search", spaceId)
                .param("q", q)
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .header("Authorization", "Bearer " + token);
        if (types != null) {
            req = req.param("types", types);
        }
        MvcResult result = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> extractIds(String json) {
        List<String> ids = new ArrayList<>();
        int idx = 0;
        while (true) {
            int at = json.indexOf("\"id\":", idx);
            if (at < 0) {
                break;
            }
            int start = at + 5;
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            ids.add(json.substring(start, end));
            idx = end;
        }
        return ids;
    }

    private Long insertSpace(String owner, String name) {
        jdbcTemplate.update(
                "INSERT INTO learning_space (name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, owner);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, owner, name);
    }

    private Long insertSource(Long spaceId, String title) {
        jdbcTemplate.update(
                "INSERT INTO source (space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'DESKTOP_UPLOAD', 'REGISTERED', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title, OWNER);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source WHERE space_id=? AND title=? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, title);
    }

    private Long insertAsset(Long spaceId, Long sourceId, String name) {
        jdbcTemplate.update(
                "INSERT INTO source_asset (space_id, source_id, asset_role, original_name, storage_key, mime_type, size_bytes, sha256, created_at) "
                        + "VALUES (?, ?, 'ORIGINAL_FILE', ?, ?, 'text/plain', 1, 'x', CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, name, "2026/09/" + name);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source_asset WHERE source_id=? AND original_name=? ORDER BY id DESC LIMIT 1",
                Long.class, sourceId, name);
    }

    private Long insertPage(Long spaceId, Long sourceId, Long assetId, int pageNo, String text) {
        jdbcTemplate.update(
                "INSERT INTO source_page (space_id, source_id, source_asset_id, source_page_number, page_order, page_type, order_status, extracted_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'BODY', 'AUTO', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, assetId, pageNo, pageNo, text);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source_page WHERE source_id=? AND source_page_number=? ORDER BY id DESC LIMIT 1",
                Long.class, sourceId, pageNo);
    }

    private void insertBlock(Long spaceId, Long sourceId, Long pageId, int order, String text) {
        jdbcTemplate.update(
                "INSERT INTO content_block (space_id, source_id, source_page_id, block_type, sort_order, normalized_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PARAGRAPH', ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, pageId, order, text);
    }

    private void insertKnowledgePoint(Long spaceId, String title, String summary) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_point (space_id, title, summary, content, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'USER_CURATED', 'PUBLISHED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title, summary, summary);
    }

    private void insertQuestion(Long spaceId, String stem, String answerJson) {
        jdbcTemplate.update(
                "INSERT INTO question (space_id, question_type, stem, answer_data_json, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, 'SINGLE_CHOICE', ?, ?, 'USER_CURATED', 'PUBLISHED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, stem, answerJson);
    }

    private void clean() {
        jdbcTemplate.update(
                "DELETE FROM content_block WHERE space_id IN (SELECT id FROM learning_space WHERE owner_subject=?)", OWNER);
        jdbcTemplate.update(
                "DELETE FROM source_page WHERE space_id IN (SELECT id FROM learning_space WHERE owner_subject=?)", OWNER);
        jdbcTemplate.update(
                "DELETE FROM source_asset WHERE space_id IN (SELECT id FROM learning_space WHERE owner_subject=?)", OWNER);
        jdbcTemplate.update(
                "DELETE FROM wrong_question WHERE space_id IN (SELECT id FROM learning_space WHERE owner_subject=?)", OWNER);
        jdbcTemplate.update(
                "DELETE FROM question WHERE space_id IN (SELECT id FROM learning_space WHERE owner_subject=?)", OWNER);
        jdbcTemplate.update(
                "DELETE FROM knowledge_point WHERE space_id IN (SELECT id FROM learning_space WHERE owner_subject=?)", OWNER);
        jdbcTemplate.update(
                "DELETE FROM source WHERE space_id IN (SELECT id FROM learning_space WHERE owner_subject=?)", OWNER);
        jdbcTemplate.update("DELETE FROM learning_space WHERE owner_subject=?", OWNER);
    }

    private void assertSchema() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("expected " + EXPECTED_SCHEMA + " got " + actual);
        }
    }
}
