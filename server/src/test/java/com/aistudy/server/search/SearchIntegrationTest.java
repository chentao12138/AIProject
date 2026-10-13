package com.aistudy.server.search;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.testsupport.OwnedSpaceReset;
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
 *
 * <p>Also defends the feature-freeze fixes:
 * <ul>
 *   <li>F-01 every {@code types} subset must render a valid UNION ALL, which
 *       {@code types=NOTE} alone and {@code types=SOURCE,NOTE} did not;</li>
 *   <li>F-02 only published / current-revision content may be searchable, so
 *       DRAFT and unreviewed rows cannot leak into the AI tutor context;</li>
 *   <li>F-03 the V054 ngram indexes drive relevance;</li>
 *   <li>F-04 NOTE hits carry {@code noteId} and {@code totalElements} agrees
 *       with the returned page.</li>
 * </ul>
 *
 * <p>FIXTURE NOTE: the source helper used to insert {@code REGISTERED} rows and
 * assert they were found. That assumption encoded the F-02 leak, so fixtures
 * now insert {@code PUBLISHED} and the unpublished states are covered by
 * explicit negative tests.
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

    // ==================== F-01: UNION ALL assembly per types subset ====================

    /**
     * types=NOTE alone used to render a LEADING {@code UNION ALL} (the NOTE
     * branch opened with one while {@code suffixOverrides} only strips a
     * trailing separator), so the request died with MySQL 1064.
     */
    @Test
    void noteAloneIsSearchableAndCarriesNoteId() throws Exception {
        Long noteId = insertNote(spaceId, "易混淆知识点笔记", "关于范式与函数依赖的个人总结");
        String body = search("范式", "NOTE", 0, 20);
        assertTrue(body.contains("\"type\":\"NOTE\""), body);
        assertTrue(body.contains("\"noteId\":" + noteId), body);
        assertTrue(body.contains("易混淆知识点笔记"), body);
    }

    /**
     * SOURCE,NOTE used to render a DOUBLED separator (SOURCE closed with one
     * and NOTE opened with one).
     */
    @Test
    void sourceAndNoteTogetherIsWellFormed() throws Exception {
        insertSource(spaceId, "范式理论教材");
        insertNote(spaceId, "范式笔记", "规范化第一范式");
        String body = search("范式", "SOURCE,NOTE", 0, 20);
        assertTrue(body.contains("\"type\":\"SOURCE\""), body);
        assertTrue(body.contains("\"type\":\"NOTE\""), body);
    }

    /**
     * WRONG_QUESTION was the branch that omitted the trailing separator, so it
     * only ever worked when it was not last.
     */
    @Test
    void wrongQuestionAndNoteTogetherIsWellFormed() throws Exception {
        Long questionId = insertQuestion(spaceId, "范式判断题？", "{\"correctOptionKey\":\"A\"}");
        insertWrongQuestion(spaceId, questionId);
        insertNote(spaceId, "范式错题笔记", "第三范式复习笔记");
        String body = search("范式", "WRONG_QUESTION,NOTE", 0, 20);
        assertTrue(body.contains("\"type\":\"WRONG_QUESTION\""), body);
        assertTrue(body.contains("\"type\":\"NOTE\""), body);
    }

    /**
     * No filter at all must keep all six branches in one valid UNION.
     */
    @Test
    void noTypeFilterCoversEveryBranch() throws Exception {
        String term = "统一检索标记";
        Long sourceId = insertSource(spaceId, term + "教材");
        Long assetId = insertAsset(spaceId, sourceId, "all.txt");
        Long pageId = insertPage(spaceId, sourceId, assetId, 1, term + "全文");
        insertBlock(spaceId, sourceId, pageId, 0, term + "正文块");
        insertKnowledgePoint(spaceId, term + "知识点", "说明");
        Long questionId = insertQuestion(spaceId, term + "题目？", "{\"correctOptionKey\":\"A\"}");
        insertWrongQuestion(spaceId, questionId);
        insertNote(spaceId, term + "笔记", "内容");

        String body = search(term, null, 0, 20);
        for (String type : List.of("SOURCE", "CONTENT_BLOCK", "KNOWLEDGE_POINT",
                "QUESTION", "WRONG_QUESTION", "NOTE")) {
            assertTrue(body.contains("\"type\":\"" + type + "\""), type + " missing: " + body);
        }
    }

    // ==================== F-02: publish / permission visibility ====================

    /**
     * REGISTERED material has not been reviewed yet, so it must not surface in
     * ordinary search (and therefore not in AI context either).
     */
    @Test
    void registeredSourceIsNotSearchable() throws Exception {
        insertSource(spaceId, "未审核教材甲", "REGISTERED");
        String body = search("未审核教材甲", "SOURCE", 0, 20);
        assertFalse(body.contains("未审核教材甲"), body);
        assertTrue(body.contains("\"totalElements\":0"), body);
    }

    /** NEEDS_REVIEW is the other half of the unpublished lifecycle. */
    @Test
    void needsReviewSourceIsNotSearchable() throws Exception {
        insertSource(spaceId, "待复核教材乙", "NEEDS_REVIEW");
        assertFalse(search("待复核教材乙", "SOURCE", 0, 20).contains("待复核教材乙"));
    }

    /** The same row becomes visible once it is PUBLISHED. */
    @Test
    void publishedSourceIsSearchable() throws Exception {
        insertSource(spaceId, "已发布教材丙", "PUBLISHED");
        assertTrue(search("已发布教材丙", "SOURCE", 0, 20).contains("已发布教材丙"));
    }

    /** A DRAFT knowledge point stays invisible while a published sibling shows. */
    @Test
    void draftKnowledgePointIsHiddenButPublishedSiblingIsVisible() throws Exception {
        insertKnowledgePoint(spaceId, "草稿隔离级别条目", "不应出现", "不应出现", "DRAFT");
        insertKnowledgePoint(spaceId, "已发布隔离级别条目", "应当出现", "应当出现", "PUBLISHED");
        String body = search("隔离级别条目", "KNOWLEDGE_POINT", 0, 20);
        assertTrue(body.contains("已发布隔离级别条目"), body);
        assertFalse(body.contains("草稿隔离级别条目"), body);
    }

    /** A DRAFT question stays invisible while a published sibling shows. */
    @Test
    void draftQuestionIsHiddenButPublishedSiblingIsVisible() throws Exception {
        insertQuestion(spaceId, "草稿题目内容？", "{\"correctOptionKey\":\"A\"}", "DRAFT");
        insertQuestion(spaceId, "已发布题目内容？", "{\"correctOptionKey\":\"A\"}", "PUBLISHED");
        String body = search("题目内容", "QUESTION", 0, 20);
        assertTrue(body.contains("已发布题目内容"), body);
        assertFalse(body.contains("草稿题目内容"), body);
    }

    /**
     * WRONG_QUESTION reads the Question through a join, so a withdrawn (DRAFT)
     * stem must disappear from the wrong-answer list too.
     */
    @Test
    void wrongQuestionOverDraftQuestionIsHidden() throws Exception {
        Long draftId = insertQuestion(spaceId, "草稿错题题干？", "{\"correctOptionKey\":\"A\"}", "DRAFT");
        insertWrongQuestion(spaceId, draftId);
        assertFalse(search("草稿错题题干", "WRONG_QUESTION", 0, 20).contains("草稿错题题干"));

        Long publishedId = insertQuestion(spaceId, "已发布错题题干？", "{\"correctOptionKey\":\"A\"}", "PUBLISHED");
        insertWrongQuestion(spaceId, publishedId);
        assertTrue(search("已发布错题题干", "WRONG_QUESTION", 0, 20).contains("已发布错题题干"));
    }

    /**
     * Rows of a superseded extraction revision stay hidden once the Source
     * points at a newer revision.
     */
    @Test
    void contentBlockOfStaleRevisionIsHidden() throws Exception {
        Long sourceId = insertSource(spaceId, "改版教材");
        Long assetId = insertAsset(spaceId, sourceId, "rev.txt");
        Long pageId = insertPage(spaceId, sourceId, assetId, 1, "当前版本全文", 77L);
        insertBlock(spaceId, sourceId, pageId, 0, "规范化当前版本内容", 77L);
        insertBlock(spaceId, sourceId, pageId, 1, "规范化陈旧版本内容", 78L);
        setCurrentRevision(sourceId, 77L);

        String body = search("规范化", "CONTENT_BLOCK", 0, 20);
        assertTrue(body.contains("规范化当前版本内容"), body);
        assertFalse(body.contains("规范化陈旧版本内容"), body);
    }

    /**
     * Pre-V053 rows carry no revision id at all: with no current revision they
     * MUST stay visible, which is why the revision predicate keeps an explicit
     * IS NULL branch.
     */
    @Test
    void contentBlockWithoutCurrentRevisionStaysVisible() throws Exception {
        Long sourceId = insertSource(spaceId, "历史教材");
        Long assetId = insertAsset(spaceId, sourceId, "legacy.txt");
        Long pageId = insertPage(spaceId, sourceId, assetId, 1, "历史全文", null);
        insertBlock(spaceId, sourceId, pageId, 0, "兼容性规范化内容", null);
        setCurrentRevision(sourceId, null);
        assertTrue(search("兼容性规范化", "CONTENT_BLOCK", 0, 20).contains("兼容性规范化内容"));
    }

    /**
     * A note whose source is unpublished must disappear even though the block
     * itself has no status column of its own.
     */
    @Test
    void contentBlockOfUnpublishedSourceIsHidden() throws Exception {
        Long sourceId = insertSource(spaceId, "未发布教材", "NEEDS_REVIEW");
        Long assetId = insertAsset(spaceId, sourceId, "draft.txt");
        Long pageId = insertPage(spaceId, sourceId, assetId, 1, "未发布正文全文");
        insertBlock(spaceId, sourceId, pageId, 0, "未发布规范化正文");
        assertFalse(search("未发布规范化", "CONTENT_BLOCK", 0, 20).contains("未发布规范化正文"));
    }

    /** Archived notes are out of scope for the caller's own search. */
    @Test
    void archivedNoteIsHidden() throws Exception {
        insertNote(spaceId, "归档范式笔记", "内容", true);
        assertFalse(search("归档范式", "NOTE", 0, 20).contains("归档范式笔记"));
    }

    // ==================== F-03: FULLTEXT relevance ====================

    /**
     * The V054 ngram indexes now decide relevance: a row whose title does not
     * contain the query as a contiguous substring is still found, because the
     * ngram tokens overlap. Before this change LIKE alone could never return it.
     */
    @Test
    void chineseFulltextFindsRowsLikeCannotMatch() throws Exception {
        insertSource(spaceId, "事务与隔离等级");
        String body = search("事务隔离", "SOURCE", 0, 20);
        assertTrue(body.contains("事务与隔离等级"),
                "MATCH ... AGAINST should surface the ngram-token overlap: " + body);
    }

    /**
     * Relevance ranking stays INSIDE the deterministic tiers: the richer
     * document must not be pushed above an exact or prefix match, but it must
     * outrank a same-tier document that mentions the term once.
     */
    @Test
    void chineseFulltextRanksRelevanceWithinTheSameTier() throws Exception {
        insertKnowledgePoint(spaceId, "深入规范化理论", "无关说明",
                "规范化规范化规范化规范化规范化", "PUBLISHED");
        insertKnowledgePoint(spaceId, "再谈规范化应用", "无关说明", "无关正文", "PUBLISHED");
        String body = search("规范化", "KNOWLEDGE_POINT", 0, 20);
        int rich = body.indexOf("深入规范化理论");
        int thin = body.indexOf("再谈规范化应用");
        assertTrue(rich >= 0 && thin >= 0, body);
        assertTrue(rich < thin, "the term-denser document must rank first: " + body);
    }

    /**
     * A term shorter than ngram_token_size has no index entry, so the LIKE
     * fallback must still find it.
     */
    @Test
    void singleCharacterQueryFallsBackToLike() throws Exception {
        insertSource(spaceId, "数据库系统概论");
        String body = search("据", "SOURCE", 0, 20);
        assertTrue(body.contains("数据库系统概论"), body);
    }

    /**
     * Punctuation-only / punctuation-bearing queries keep literal LIKE
     * semantics: FULLTEXT ignores % and _ as delimiters, so it must never
     * decide which rows are returned for such a query.
     */
    @Test
    void punctuatedQueryKeepsLiteralLikeSemantics() throws Exception {
        insertSource(spaceId, "50%折扣");
        insertSource(spaceId, "50X折扣");
        String body = search("%折扣", "SOURCE", 0, 20);
        assertTrue(body.contains("50%折扣"), body);
        assertFalse(body.contains("50X折扣"), "wildcard-like input must stay literal: " + body);
    }

    // ==================== F-04: noteId contract and count/page agreement ====

    /**
     * Only NOTE hits expose noteId; every other type must serialize it as null
     * so a client cannot mistake another entity for a note.
     */
    @Test
    void noteIdIsOnlyPresentOnNoteHits() throws Exception {
        Long noteId = insertNote(spaceId, "契约笔记", "noteId 契约内容");
        insertSource(spaceId, "契约教材");
        String noteBody = search("契约", "NOTE", 0, 20);
        assertTrue(noteBody.contains("\"noteId\":" + noteId), noteBody);

        String sourceBody = search("契约", "SOURCE", 0, 20);
        assertTrue(sourceBody.contains("\"type\":\"SOURCE\""), sourceBody);
        assertTrue(sourceBody.contains("\"noteId\":null"), sourceBody);
    }

    /**
     * totalElements must agree with the returned page: the count used to score
     * a contains-only knowledge-point title against the PREFIX pattern, so it
     * reported 0 for a row the page returned.
     */
    @Test
    void totalElementsMatchesPageForContainsOnlyTitle() throws Exception {
        insertKnowledgePoint(spaceId, "深入事务隔离理论", "无关说明", "无关正文", "PUBLISHED");
        String body = search("事务隔离理论", "KNOWLEDGE_POINT", 0, 20);
        assertTrue(body.contains("深入事务隔离理论"), body);
        assertTrue(body.contains("\"totalElements\":1"), body);
        assertTrue(body.contains("\"totalPages\":1"), body);
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
        return insertSource(spaceId, title, "PUBLISHED");
    }

    /**
     * Inserts a Source in an explicit lifecycle status.
     *
     * @param spaceId owning space
     * @param title   source title
     * @param status  REGISTERED / PROCESSING / NEEDS_REVIEW / PUBLISHED; only
     *                PUBLISHED may be found by search (F-02)
     * @return generated id
     */
    private Long insertSource(Long spaceId, String title, String status) {
        jdbcTemplate.update(
                "INSERT INTO source (space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'DESKTOP_UPLOAD', ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title, status, OWNER);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source WHERE space_id=? AND title=? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, title);
    }

    /**
     * Points a Source at its current extraction revision (V053 ownership).
     *
     * @param sourceId   source to update
     * @param revisionId revision id, or NULL for pre-V053 compatibility
     */
    private void setCurrentRevision(Long sourceId, Long revisionId) {
        jdbcTemplate.update("UPDATE source SET current_extraction_revision_id = ? WHERE id = ?",
                revisionId, sourceId);
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
        return insertPage(spaceId, sourceId, assetId, pageNo, text, null);
    }

    /**
     * @param spaceId    owning space
     * @param sourceId   owning source
     * @param assetId    provenance asset
     * @param pageNo     page number
     * @param text       extracted text
     * @param revisionId extraction revision the page belongs to, or NULL for
     *                   pre-V053 rows
     * @return generated page id
     */
    private Long insertPage(Long spaceId, Long sourceId, Long assetId, int pageNo,
                            String text, Long revisionId) {
        jdbcTemplate.update(
                "INSERT INTO source_page (space_id, source_id, source_asset_id, source_page_number, page_order, "
                        + "page_type, order_status, extracted_text, extraction_revision_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'BODY', 'AUTO', ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, assetId, pageNo, pageNo, text, revisionId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM source_page WHERE source_id=? AND source_page_number=? ORDER BY id DESC LIMIT 1",
                Long.class, sourceId, pageNo);
    }

    private void insertBlock(Long spaceId, Long sourceId, Long pageId, int order, String text) {
        insertBlock(spaceId, sourceId, pageId, order, text, null);
    }

    /**
     * @param spaceId    owning space
     * @param sourceId   owning source
     * @param pageId     owning page
     * @param order      block sort order
     * @param text       normalized block text
     * @param revisionId extraction revision the block belongs to, or NULL for
     *                   pre-V053 rows
     */
    private void insertBlock(Long spaceId, Long sourceId, Long pageId, int order,
                             String text, Long revisionId) {
        jdbcTemplate.update(
                "INSERT INTO content_block (space_id, source_id, source_page_id, block_type, sort_order, "
                        + "normalized_text, extraction_revision_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PARAGRAPH', ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, pageId, order, text, revisionId);
    }

    private void insertKnowledgePoint(Long spaceId, String title, String summary) {
        insertKnowledgePoint(spaceId, title, summary, summary, "PUBLISHED");
    }

    /**
     * @param spaceId owning space
     * @param title   knowledge point title
     * @param summary summary text
     * @param content body text
     * @param status  DRAFT or PUBLISHED; only PUBLISHED is searchable (F-02)
     */
    private void insertKnowledgePoint(Long spaceId, String title, String summary,
                                      String content, String status) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_point (space_id, title, summary, content, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'USER_CURATED', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, title, summary, content, status);
    }

    private Long insertQuestion(Long spaceId, String stem, String answerJson) {
        return insertQuestion(spaceId, stem, answerJson, "PUBLISHED");
    }

    /**
     * @param spaceId     owning space
     * @param stem        question stem
     * @param answerJson  answer payload, never projected by search
     * @param status      DRAFT or PUBLISHED; only PUBLISHED is searchable (F-02)
     * @return generated question id
     */
    private Long insertQuestion(Long spaceId, String stem, String answerJson, String status) {
        jdbcTemplate.update(
                "INSERT INTO question (space_id, question_type, stem, answer_data_json, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, 'SINGLE_CHOICE', ?, ?, 'USER_CURATED', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, stem, answerJson, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM question WHERE space_id=? AND stem=? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, stem);
    }

    /**
     * @param spaceId owning space
     * @param title   note title
     * @param content note body
     * @return generated note id
     */
    private Long insertNote(Long spaceId, String title, String content) {
        return insertNote(spaceId, title, content, false);
    }

    /**
     * @param spaceId  owning space
     * @param title    note title
     * @param content  note body
     * @param archived when true the note is soft-archived and must not be found
     * @return generated note id
     */
    private Long insertNote(Long spaceId, String title, String content, boolean archived) {
        jdbcTemplate.update(
                "INSERT INTO note (space_id, user_subject_id, title, content, content_format, "
                        + "created_at, updated_at, archived_at) "
                        + "VALUES (?, ?, ?, ?, 'PLAIN_TEXT', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), ?)",
                spaceId, OWNER, title, content,
                archived ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM note WHERE space_id=? AND title=? ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, title);
    }

    /**
     * @param spaceId    owning space
     * @param questionId question the caller answered wrongly
     */
    private void insertWrongQuestion(Long spaceId, Long questionId) {
        jdbcTemplate.update(
                "INSERT INTO wrong_question (user_subject, space_id, question_id, first_wrong_at, last_wrong_at, "
                        + "wrong_count, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 'ACTIVE', "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                OWNER, spaceId, questionId);
    }

    private void clean() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, OWNER);
    }

    private void assertSchema() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("expected " + EXPECTED_SCHEMA + " got " + actual);
        }
    }
}
