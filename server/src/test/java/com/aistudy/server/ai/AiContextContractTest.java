package com.aistudy.server.ai;

import com.aistudy.server.ai.dto.AiDto.ConversationView;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.provider.AiUsage;
import com.aistudy.server.ai.service.AiConversationService;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.testsupport.OwnedSpaceReset;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C03 / C04 — AI tutor context contract, vertical slice on real MySQL: the
 * client may only name entities by ID, the server validates that ID against the
 * conversation's LearningSpace, retrieves the material itself, grounds the
 * provider request on it, and answers with citations structured enough to
 * deep-link
 * {@code /spaces/{spaceId}/sources/{sourceId}?page={sourcePageId}&block={contentBlockId}}
 * — on the live turn and on every later page load.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiContextContractTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String OWNER = "biz-ai-context-user";
    private static final String INTRUDER = "biz-ai-context-intruder";

    private static final String CONVERSATIONS = "/api/v1/spaces/{spaceId}/ai/conversations";
    private static final String MESSAGES =
            "/api/v1/spaces/{spaceId}/ai/conversations/{conversationId}/messages";

    /** Matches no material in the fixtures, so only the selection can be grounded. */
    private static final String QUESTION = "这段内容与考试无关的疑问？";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;
    @Autowired
    private AiConversationService conversationService;

    @MockitoBean
    private AiProvider aiProvider;

    private Long spaceId;
    private Long intruderSpaceId;
    private Long sourceId;
    private Long pageId;
    private Long blockId;
    private Long knowledgePointId;
    private Long conversationId;

    @DynamicPropertySource
    static void aiProps(DynamicPropertyRegistry registry) {
        registry.add("aistudy.ai.enabled", () -> "true");
        registry.add("aistudy.ai.base-url", () -> "http://127.0.0.1:9");
        registry.add("aistudy.ai.api-key", () -> "test-api-key");
        registry.add("aistudy.ai.model", () -> "test-model");
    }

    @BeforeEach
    void prepare() {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        clean();
        spaceId = insertSpace(OWNER, "AI 上下文空间");
        intruderSpaceId = insertSpace(INTRUDER, "别人的空间");
        sourceId = insertSource(spaceId, "数据库教材");
        pageId = insertPage(spaceId, sourceId, 7);
        blockId = insertBlock(spaceId, sourceId, pageId, "B+ 树叶子节点自左向右相连");
        knowledgePointId = insertKnowledgePoint(spaceId, "B+ 树分裂", "节点写满时拆分为两层索引");
        conversationId = conversationService.createConversation(OWNER, spaceId, "契约").id();

        when(aiProvider.chat(any(), anyString())).thenReturn(new AiChatResponse(
                "根据你选中的资料：叶子节点相连。", "openai-compatible", "test-model", AiUsage.empty()));
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    // ==================== C03: selection reaches the provider ====================

    /** (1) a content-block ID is enough: the server fetches the block text itself. */
    @Test
    void selectedContentBlockTextReachesTheProviderRequest() throws Exception {
        MvcResult result = mockMvc.perform(post(MESSAGES, spaceId, conversationId)
                        .header("Authorization", "Bearer " + token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + QUESTION + "\",\"contentBlockId\":" + blockId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertNotNull(result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        String sent = String.join("\n", capturedProviderMessages());
        assertTrue(sent.contains("B+ 树叶子节点自左向右相连"),
                "the selected block body must reach the provider: " + sent);
        assertTrue(sent.contains("learner-selected"),
                "selected material must be marked so it outranks retrieved material");

        // the client sent IDs only; nothing but the question is echoed as content
        assertTrue(sent.contains(QUESTION));
    }

    /** (2) the reply comes back with a routable citation, not just a label. */
    @Test
    void blockCitationCarriesTheWholeDeepLinkRoute() throws Exception {
        mockMvc.perform(post(MESSAGES, spaceId, conversationId)
                        .header("Authorization", "Bearer " + token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + QUESTION + "\",\"contentBlockId\":" + blockId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextReferences[0].type").value("CONTENT_BLOCK"))
                .andExpect(jsonPath("$.contextReferences[0].id").value(blockId))
                .andExpect(jsonPath("$.contextReferences[0].contentBlockId").value(blockId))
                .andExpect(jsonPath("$.contextReferences[0].sourcePageId").value(pageId))
                .andExpect(jsonPath("$.contextReferences[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.contextReferences[0].locator").value("P7"))
                .andExpect(jsonPath("$.assistantMessage.references[0].sourceId").value(sourceId));
    }

    /** (3) a page selection is its own reference level and survives the check constraint. */
    @Test
    void pageSelectionIsPersistedAsOwnReferenceType() throws Exception {
        mockMvc.perform(post(MESSAGES, spaceId, conversationId)
                        .header("Authorization", "Bearer " + token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + QUESTION + "\",\"sourcePageId\":" + pageId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextReferences[0].type").value("SOURCE_PAGE"))
                .andExpect(jsonPath("$.contextReferences[0].id").value(pageId))
                .andExpect(jsonPath("$.contextReferences[0].sourcePageId").value(pageId))
                .andExpect(jsonPath("$.contextReferences[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.contextReferences[0].contentBlockId").value(
                        org.hamcrest.Matchers.nullValue()));

        String sent = String.join("\n", capturedProviderMessages());
        assertTrue(sent.contains("第七页正文"), "the page body must be retrieved server-side: " + sent);
    }

    /** (4) a Source-level question gets the source's own text, bounded. */
    @Test
    void sourceSelectionBringsItsBlocksIntoContext() throws Exception {
        mockMvc.perform(post(MESSAGES, spaceId, conversationId)
                        .header("Authorization", "Bearer " + token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + QUESTION + "\",\"sourceId\":" + sourceId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextReferences[0].type").value("SOURCE"))
                .andExpect(jsonPath("$.contextReferences[0].sourceId").value(sourceId));

        String sent = String.join("\n", capturedProviderMessages());
        assertTrue(sent.contains("B+ 树叶子节点自左向右相连"),
                "a Source selection must ground on that source's text: " + sent);
    }

    /** (5) knowledge points are selected by ID and grounded on their own body. */
    @Test
    void knowledgePointSelectionIsGrounded() throws Exception {
        mockMvc.perform(post(MESSAGES, spaceId, conversationId)
                        .header("Authorization", "Bearer " + token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + QUESTION + "\",\"knowledgePointId\":"
                                + knowledgePointId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextReferences[0].type").value("KNOWLEDGE_POINT"))
                .andExpect(jsonPath("$.contextReferences[0].id").value(knowledgePointId));

        String sent = String.join("\n", capturedProviderMessages());
        assertTrue(sent.contains("节点写满时拆分为两层索引"),
                "the knowledge point body must reach the provider: " + sent);
    }

    // ==================== C04: citations survive a page load ====================

    /** (6) the reference rows store the route, so a reload reproduces the citations. */
    @Test
    void persistedReferencesRebuildStructuredCitations() throws Exception {
        mockMvc.perform(post(MESSAGES, spaceId, conversationId)
                        .header("Authorization", "Bearer " + token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + QUESTION + "\",\"contentBlockId\":" + blockId + "}"))
                .andExpect(status().isCreated());

        java.util.Map<String, Object> stored = jdbc.queryForMap(
                "SELECT r.source_id, r.source_page_id, r.content_block_id, r.locator, r.reference_type "
                        + "FROM ai_message_reference r JOIN ai_message m ON m.id = r.message_id "
                        + "WHERE m.conversation_id = ?", conversationId);
        assertEquals("CONTENT_BLOCK", stored.get("reference_type"));
        assertEquals(sourceId, stored.get("source_id"));
        assertEquals(pageId, stored.get("source_page_id"));
        assertEquals(blockId, stored.get("content_block_id"));
        assertEquals("P7", stored.get("locator"));

        // USER is content[0], ASSISTANT content[1] (created_at ASC, id ASC)
        mockMvc.perform(get(MESSAGES, spaceId, conversationId)
                        .header("Authorization", "Bearer " + token(OWNER))
                        .param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("USER"))
                .andExpect(jsonPath("$.content[1].references[0].type").value("CONTENT_BLOCK"))
                .andExpect(jsonPath("$.content[1].references[0].contentBlockId").value(blockId))
                .andExpect(jsonPath("$.content[1].references[0].sourcePageId").value(pageId))
                .andExpect(jsonPath("$.content[1].references[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.content[1].references[0].locator").value("P7"));
    }

    // ==================== C03: validation, before anything is written ====================

    /** (7) IDs outside the conversation's LearningSpace are 400 VALIDATION_ERROR. */
    @Test
    void foreignSpaceIdsAreRejectedWithoutWritingAMessage() throws Exception {
        Long foreignSource = insertSource(intruderSpaceId, "别人的教材");
        Long foreignPage = insertPage(intruderSpaceId, foreignSource, 1);
        Long foreignBlock = insertBlock(intruderSpaceId, foreignSource, foreignPage, "别人的正文");
        Long foreignPoint = insertKnowledgePoint(intruderSpaceId, "别人的知识点", "正文");

        assertProblem(postMessage("\"content\":\"" + QUESTION + "\",\"sourceId\":" + foreignSource),
                400, "VALIDATION_ERROR");
        assertProblem(postMessage("\"content\":\"" + QUESTION + "\",\"sourcePageId\":" + foreignPage),
                400, "VALIDATION_ERROR");
        assertProblem(postMessage("\"content\":\"" + QUESTION + "\",\"contentBlockId\":" + foreignBlock),
                400, "VALIDATION_ERROR");
        assertProblem(postMessage("\"content\":\"" + QUESTION + "\",\"knowledgePointId\":" + foreignPoint),
                400, "VALIDATION_ERROR");
        assertProblem(postMessage("\"content\":\"" + QUESTION + "\",\"contentBlockId\":99999999"),
                400, "VALIDATION_ERROR");

        assertEquals(0, messageCount(), "a rejected turn must not leave a half-written message behind");
        verify(aiProvider, never()).chat(any(), anyString());
    }

    /** (8) soft-deleted knowledge points are not selectable. */
    @Test
    void softDeletedKnowledgePointIsRejected() throws Exception {
        jdbc.update("UPDATE knowledge_point SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                knowledgePointId);

        assertProblem(postMessage("\"content\":\"" + QUESTION + "\",\"knowledgePointId\":" + knowledgePointId),
                400, "VALIDATION_ERROR");
        assertEquals(0, messageCount());
    }

    /** (9) a page that is not inside the given source is rejected too. */
    @Test
    void pageOutsideTheGivenSourceIsRejected() throws Exception {
        Long otherSource = insertSource(spaceId, "另一本教材");
        Long pageOfOther = insertPage(spaceId, otherSource, 3);

        assertProblem(postMessage("\"content\":\"" + QUESTION + "\",\"sourceId\":" + sourceId
                + ",\"sourcePageId\":" + pageOfOther), 400, "VALIDATION_ERROR");
        assertEquals(0, messageCount());
    }

    /** (10) the same request without any ID still works (contract is additive). */
    @Test
    void plainTextTurnIsUnaffected() throws Exception {
        mockMvc.perform(post(MESSAGES, spaceId, conversationId)
                        .header("Authorization", "Bearer " + token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + QUESTION + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assistantMessage.role").value("ASSISTANT"));
    }

    // ==================== helpers ====================

    private String token(String subject) {
        String token = jwtAccessTokenService.issueAccessToken(subject);
        assertNotNull(token, "token must not be null");
        return token;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postMessage(
            String bodyFields) {
        return post(MESSAGES, spaceId, conversationId)
                .header("Authorization", "Bearer " + token(OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + bodyFields + "}");
    }

    private List<String> capturedProviderMessages() {
        ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(aiProvider, atLeastOnce()).chat(captor.capture(), anyString());
        AiChatRequest last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        return last.messages().stream().map(message -> message.content()).toList();
    }

    private int messageCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id = ?", Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private void assertProblem(org.springframework.test.web.servlet.RequestBuilder call,
                               int status, String code) throws Exception {
        MvcResult result = mockMvc.perform(call).andReturn();
        assertEquals(status, result.getResponse().getStatus(),
                "body: " + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        var problem = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(code, problem.get("code").asText(), "problem: " + problem);
    }

    private Long insertSpace(String owner, String name) {
        jdbc.update("INSERT INTO learning_space "
                        + "(name, description, owner_subject, status, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                name, owner);
        return jdbc.queryForObject(
                "SELECT id FROM learning_space WHERE owner_subject=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, owner, name);
    }

    private Long insertSource(Long space, String title) {
        jdbc.update("INSERT INTO source (space_id, title, source_type, status, created_by_user_id, "
                        + "created_at, updated_at) VALUES (?, ?, 'UPLOAD', 'ACTIVE', ?, "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                space, title, OWNER);
        return lastId("source");
    }

    private Long insertPage(Long space, Long source, Integer pageNumber) {
        jdbc.update("INSERT INTO source_page (space_id, source_id, source_page_number, page_order, "
                        + "page_type, order_status, extracted_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'BODY', 'AUTO', '第七页正文', "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                space, source, pageNumber, pageNumber);
        return lastId("source_page");
    }

    private Long insertBlock(Long space, Long source, Long page, String text) {
        jdbc.update("INSERT INTO content_block (space_id, source_id, source_page_id, block_type, "
                        + "sort_order, normalized_text, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PARAGRAPH', 0, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                space, source, page, text);
        return lastId("content_block");
    }

    private Long insertKnowledgePoint(Long space, String title, String content) {
        jdbc.update("INSERT INTO knowledge_point (space_id, title, content, origin_type, status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'AI_EXTRACTED', 'PUBLISHED', "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                space, title, content);
        return lastId("knowledge_point");
    }

    private Long lastId(String table) {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
        assertNotNull(id, table + " fixture must produce an id");
        return id;
    }

    private void clean() {
        OwnedSpaceReset.forSubjects(jdbc, OWNER, INTRUDER);
    }

    private void assertSchema() {
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("expected " + EXPECTED_SCHEMA + " got " + actual);
        }
    }
}
