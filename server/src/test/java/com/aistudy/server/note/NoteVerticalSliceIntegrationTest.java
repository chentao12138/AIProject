package com.aistudy.server.note;

import com.aistudy.server.auth.service.JwtAccessTokenService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notes contract (C-01) — vertical slice against real MySQL under the
 * {@code flyway-it} profile: no mocks, real JWTs, real V043/V057 schema.
 *
 * <p>Covers the five verbs, the paged/keyword list, the primary-context
 * pointers (including server-side derivation of page and source from a
 * content block), same-space validation of every context ID, and the
 * ownership rules that keep one user's notes invisible to another.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoteVerticalSliceIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final List<String> BIZ_TEST_USERS = List.of("biz-note-user-1", "biz-note-user-2");

    private static final String NOTES = "/api/v1/spaces/{spaceId}/notes";
    private static final String NOTE = "/api/v1/spaces/{spaceId}/notes/{noteId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

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
        cleanBizTestRows();
    }

    @AfterEach
    void cleanUp() {
        cleanBizTestRows();
    }

    // ==================== helpers ====================

    private String tokenFor(String subject) {
        String token = jwtAccessTokenService.issueAccessToken(subject);
        assertNotNull(token, "token must not be null");
        return token;
    }

    private Long insertSpace(String ownerSubject, String name) {
        jdbcTemplate.update(
                "INSERT INTO learning_space (name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, ownerSubject);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject = ? AND name = ? LIMIT 1",
                Long.class, ownerSubject, name);
    }

    private Long insertSource(Long spaceId, String owner) {
        jdbcTemplate.update(
                "INSERT INTO source (space_id, title, source_type, status, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, '教材', 'UPLOAD', 'ACTIVE', ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, owner);
        return lastId("source");
    }

    private Long insertPage(Long spaceId, Long sourceId) {
        jdbcTemplate.update(
                "INSERT INTO source_page (space_id, source_id, source_page_number, page_order, page_type, "
                        + "order_status, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 1, 'BODY', 'AUTO', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, sourceId);
        return lastId("source_page");
    }

    private Long insertBlock(Long spaceId, Long sourceId, Long pageId) {
        jdbcTemplate.update(
                "INSERT INTO content_block (space_id, source_id, source_page_id, block_type, sort_order, "
                        + "normalized_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PARAGRAPH', 0, '第二章 索引结构', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId, sourceId, pageId);
        return lastId("content_block");
    }

    private Long insertKnowledgePoint(Long spaceId) {
        jdbcTemplate.update(
                "INSERT INTO knowledge_point (space_id, title, content, origin_type, status, created_at, updated_at) "
                        + "VALUES (?, 'B+ 树', '插入与删除', 'AI_EXTRACTED', 'PUBLISHED', "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId);
        return lastId("knowledge_point");
    }

    private Long insertQuestion(Long spaceId) {
        jdbcTemplate.update(
                "INSERT INTO question (space_id, question_type, stem, answer_data_json, origin_type, status, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'SHORT_ANSWER', '简述 B+ 树的查找过程', '{\"referenceAnswer\":\" descend \"}', "
                        + "'USER_CURATED', 'DRAFT', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                spaceId);
        return lastId("question");
    }

    private Long lastId(String table) {
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
        assertNotNull(id, table + " fixture must produce an id");
        return id;
    }

    /** Creates a note through the API and returns its id. */
    private Long createNote(String token, Long spaceId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    private Long readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        var problem = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(code, problem.get("code").asText(),
                "expected error code " + code + " but got " + problem);
    }

    private void cleanBizTestRows() {
        OwnedSpaceReset.forSubjects(jdbcTemplate, BIZ_TEST_USERS);
    }

    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run NoteVerticalSliceIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'.");
        }
    }

    // ==================== create / read ====================

    /** (1) POST returns a NoteView (never the entity) and the row is persisted. */
    @Test
    void createReturnsTypedViewAndPersists() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");

        mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"索引复习\",\"content\":\"B+ 树叶子节点相连\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.title").value("索引复习"))
                .andExpect(jsonPath("$.content").value("B+ 树叶子节点相连"))
                .andExpect(jsonPath("$.contentFormat").value("PLAIN_TEXT"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                // the entity's owner column must never be echoed
                .andExpect(jsonPath("$.userSubject").doesNotExist());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM note WHERE space_id = ? AND title = ?", Integer.class,
                spaceId, "索引复习");
        assertEquals(1, count);
    }

    /** (2) title is optional: an untitled note persists with a NULL title. */
    @Test
    void untitledNoteIsAccepted() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");

        mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"只有正文\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(nullValue()));
    }

    /** (3) blank content is a validation failure, not a 500 or a silent row. */
    @Test
    void blankContentIsRejected() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");

        assertProblem(mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"空\",\"content\":\"  \"}"))
                .andReturn(), 400, "VALIDATION_ERROR");
    }

    /** (4) MARKDOWN is stored as given; an unknown format is rejected. */
    @Test
    void contentFormatIsValidated() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");

        mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# 标题\",\"contentFormat\":\"markdown\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentFormat").value("MARKDOWN"));

        assertProblem(mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"contentFormat\":\"LATEX\"}"))
                .andReturn(), 400, "VALIDATION_ERROR");
    }

    // ==================== list ====================

    /** (5) the list is a pagination envelope, newest change first. */
    @Test
    void listIsPagedAndNewestFirst() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        for (int i = 1; i <= 3; i++) {
            createNote(token, spaceId, "{\"title\":\"笔记" + i + "\",\"content\":\"正文" + i + "\"}");
        }

        mockMvc.perform(get(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].title").value("笔记3"));

        mockMvc.perform(get(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("笔记1"));
    }

    /** (6) keyword matches title and body. */
    @Test
    void keywordFiltersTitleAndContent() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        createNote(token, spaceId, "{\"title\":\"算法\",\"content\":\"快速排序\"}");
        createNote(token, spaceId, "{\"title\":\"网络\",\"content\":\"三次握手\"}");

        mockMvc.perform(get(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", "握手"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("网络"));
    }

    /** (7) a keyword containing LIKE wildcards is matched literally. */
    @Test
    void keywordWildcardsAreEscaped() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        createNote(token, spaceId, "{\"title\":\"百分数\",\"content\":\"50% 的题\"}");
        createNote(token, spaceId, "{\"title\":\"普通\",\"content\":\"没有符号\"}");

        mockMvc.perform(get(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("百分数"));
    }

    // ==================== context ====================

    /** (8) a content block reference derives its page and source, so the note is navigable. */
    @Test
    void blockContextDerivesPageAndSource() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long sourceId = insertSource(spaceId, "biz-note-user-1");
        Long pageId = insertPage(spaceId, sourceId);
        Long blockId = insertBlock(spaceId, sourceId, pageId);

        mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"摘录\",\"contentFormat\":\"MARKDOWN\",\"contentBlockId\":" + blockId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentBlockId").value(blockId))
                .andExpect(jsonPath("$.sourcePageId").value(pageId))
                .andExpect(jsonPath("$.sourceId").value(sourceId));
    }

    /** (9) knowledge point and question references are echoed back. */
    @Test
    void knowledgePointAndQuestionReferences() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long kpId = insertKnowledgePoint(spaceId);
        Long questionId = insertQuestion(spaceId);

        mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"关联\",\"knowledgePointId\":" + kpId
                                + ",\"questionId\":" + questionId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.knowledgePointId").value(kpId))
                .andExpect(jsonPath("$.questionId").value(questionId))
                .andExpect(jsonPath("$.sourceId").value(nullValue()));
    }

    /** (10) a reference into someone else's space is a validation error, not a link. */
    @Test
    void contextFromAnotherSpaceIsRejected() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long mySpace = insertSpace("biz-note-user-1", "我的空间");
        Long otherSpace = insertSpace("biz-note-user-2", "别人的空间");
        Long otherSource = insertSource(otherSpace, "biz-note-user-2");
        Long otherPage = insertPage(otherSpace, otherSource);
        Long otherBlock = insertBlock(otherSpace, otherSource, otherPage);
        Long otherKp = insertKnowledgePoint(otherSpace);
        Long otherQuestion = insertQuestion(otherSpace);

        assertProblem(mockMvc.perform(post(NOTES, mySpace)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"sourceId\":" + otherSource + "}"))
                .andReturn(), 400, "VALIDATION_ERROR");
        assertProblem(mockMvc.perform(post(NOTES, mySpace)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"sourcePageId\":" + otherPage + "}"))
                .andReturn(), 400, "VALIDATION_ERROR");
        assertProblem(mockMvc.perform(post(NOTES, mySpace)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"contentBlockId\":" + otherBlock + "}"))
                .andReturn(), 400, "VALIDATION_ERROR");
        assertProblem(mockMvc.perform(post(NOTES, mySpace)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"knowledgePointId\":" + otherKp + "}"))
                .andReturn(), 400, "VALIDATION_ERROR");
        assertProblem(mockMvc.perform(post(NOTES, mySpace)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"questionId\":" + otherQuestion + "}"))
                .andReturn(), 400, "VALIDATION_ERROR");
    }

    /** (11) a page that is not inside the given source is rejected too. */
    @Test
    void pageMustBelongToTheGivenSource() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long sourceA = insertSource(spaceId, "biz-note-user-1");
        Long sourceB = insertSource(spaceId, "biz-note-user-1");
        Long pageOfB = insertPage(spaceId, sourceB);

        assertProblem(mockMvc.perform(post(NOTES, spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"sourceId\":" + sourceA + ",\"sourcePageId\":" + pageOfB + "}"))
                .andReturn(), 400, "VALIDATION_ERROR");
    }

    // ==================== update / delete / archive ====================

    /** (12) PUT replaces the document and clears omitted context pointers. */
    @Test
    void updateReplacesDocumentAndClearsContext() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long sourceId = insertSource(spaceId, "biz-note-user-1");
        Long pageId = insertPage(spaceId, sourceId);
        Long blockId = insertBlock(spaceId, sourceId, pageId);
        Long noteId = createNote(token, spaceId,
                "{\"title\":\"旧\",\"content\":\"旧正文\",\"contentBlockId\":" + blockId + "}");

        mockMvc.perform(put(NOTE, spaceId, noteId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新\",\"content\":\"新正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(noteId))
                .andExpect(jsonPath("$.title").value("新"))
                .andExpect(jsonPath("$.content").value("新正文"))
                .andExpect(jsonPath("$.contentBlockId").value(nullValue()))
                .andExpect(jsonPath("$.sourcePageId").value(nullValue()))
                .andExpect(jsonPath("$.sourceId").value(nullValue()));

        // The response used to be the only evidence: updateById skips null
        // fields, so the row kept the old block while the body said it was gone.
        assertNull(
                jdbcTemplate.queryForObject(
                        "SELECT content_block_id FROM note WHERE id = ?", Long.class, noteId),
                "omitted context pointer must be cleared in the row");
    }

    /** (13) DELETE removes the note and its M:N link rows. */
    @Test
    void deleteRemovesNoteAndLinks() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long sourceId = insertSource(spaceId, "biz-note-user-1");
        Long pageId = insertPage(spaceId, sourceId);
        Long blockId = insertBlock(spaceId, sourceId, pageId);
        Long kpId = insertKnowledgePoint(spaceId);
        Long noteId = createNote(token, spaceId, "{\"content\":\"待删除\"}");

        jdbcTemplate.update("INSERT INTO note_source (note_id, space_id, content_block_id, relation_type, created_at) "
                + "VALUES (?, ?, ?, 'QUOTE', CURRENT_TIMESTAMP(6))", noteId, spaceId, blockId);
        jdbcTemplate.update("INSERT INTO note_knowledge_point (note_id, space_id, knowledge_point_id, created_at) "
                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))", noteId, spaceId, kpId);

        mockMvc.perform(delete(NOTE, spaceId, noteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertEquals(0, countRows("note", noteId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM note_source WHERE note_id = ?", Integer.class, noteId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM note_knowledge_point WHERE note_id = ?", Integer.class, noteId));
        mockMvc.perform(get(NOTE, spaceId, noteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** (14) archiving hides the note from the list without deleting it. */
    @Test
    void archivedNoteDisappearsFromList() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long noteId = createNote(token, spaceId, "{\"content\":\"要归档\"}");

        mockMvc.perform(post(NOTES + "/{noteId}/archive", spaceId, noteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(NOTES, spaceId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        assertEquals(1, countRows("note", noteId), "archive must not delete the row");
    }

    /**
     * (14b) C-Notes-archive: archive was one-way and invisible — the row left the
     * list, {@code NoteView} had no {@code archivedAt}, and no verb could bring
     * the note back. The archive tab and restore close both halves.
     */
    @Test
    void archiveIsVisibleAndRestorable() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long noteId = createNote(token, spaceId, "{\"content\":\"要归档\"}");

        mockMvc.perform(post(NOTES + "/{noteId}/archive", spaceId, noteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(NOTES, spaceId).param("archived", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(noteId))
                .andExpect(jsonPath("$.items[0].archivedAt").exists());

        mockMvc.perform(post(NOTES + "/{noteId}/restore", spaceId, noteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // updateById would have skipped the null archived_at and answered 204
        // anyway, leaving the note stuck in the archive.
        mockMvc.perform(get(NOTES, spaceId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].archivedAt").value(nullValue()));
    }

    /**
     * (14c) C-Notes-reverse: A8.3 only had the note → entity direction. The
     * entity filter lists the notes attached through the M:N table, which is
     * where a multi-link note actually lives.
     */
    @Test
    void listFiltersByLinkedKnowledgePoint() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long kpId = insertKnowledgePoint(spaceId);
        Long otherKpId = insertKnowledgePoint(spaceId);
        Long noteId = createNote(token, spaceId, "{\"content\":\" linked\"}");
        mockMvc.perform(post(NOTES + "/{noteId}/knowledge-points", spaceId, noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + kpId + "]")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        createNote(token, spaceId, "{\"content\":\"无关\"}");

        mockMvc.perform(get(NOTES, spaceId).param("knowledgePointId", String.valueOf(kpId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(noteId));

        mockMvc.perform(get(NOTES, spaceId).param("knowledgePointId", String.valueOf(otherKpId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    /**
     * (14d) C-Notes-link-noop: an id that is not in the caller's space used to
     * make the service return early while the controller still answered 201 with
     * no body, so "linked" and "ignored" were the same response.
     */
    @Test
    void linkingAnUnknownKnowledgePointIsRejected() throws Exception {
        String token = tokenFor("biz-note-user-1");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long noteId = createNote(token, spaceId, "{\"content\":\"正文\"}");

        mockMvc.perform(post(NOTES + "/{noteId}/knowledge-points", spaceId, noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[987654321]")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_POINT_NOT_FOUND"));

        mockMvc.perform(post(NOTES + "/{noteId}/knowledge-points", spaceId, 987654321L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + insertKnowledgePoint(spaceId) + "]")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTE_NOT_FOUND"));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM note_knowledge_point WHERE note_id = ?",
                Integer.class, noteId));
    }

    // ==================== ownership ====================

    /** (15) another user's note is invisible on every verb (404, never 403). */
    @Test
    void otherUsersNoteIsInvisible() throws Exception {
        String ownerToken = tokenFor("biz-note-user-1");
        String intruderToken = tokenFor("biz-note-user-2");
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");
        Long noteId = createNote(ownerToken, spaceId, "{\"content\":\"私密\"}");

        mockMvc.perform(get(NOTE, spaceId, noteId).header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(NOTE, spaceId, noteId)
                        .header("Authorization", "Bearer " + intruderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"改写\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(NOTE, spaceId, noteId).header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(NOTES, spaceId).header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());

        assertEquals(1, countRows("note", noteId));
    }

    /** (16) anonymous requests are rejected before any note is touched. */
    @Test
    void anonymousCallsAreUnauthorized() throws Exception {
        Long spaceId = insertSpace("biz-note-user-1", "笔记空间");

        mockMvc.perform(get(NOTES, spaceId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(NOTES, spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    private int countRows(String table, Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
