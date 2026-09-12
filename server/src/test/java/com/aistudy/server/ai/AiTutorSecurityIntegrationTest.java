package com.aistudy.server.ai;

import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.provider.AiUsage;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI-004 — HTTP security: anonymous, ownership, isolation, no secret leak.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiTutorSecurityIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String USER_A = "biz-ai-sec-a";
    private static final String USER_B = "biz-ai-sec-b";
    private static final String BASE = "/api/v1/spaces/{spaceId}/ai";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @MockitoBean
    private AiProvider aiProvider;

    private Long spaceA;
    private Long spaceB;
    private String tokenA;
    private String tokenB;

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
        spaceA = insertSpace(USER_A, "SecA");
        spaceB = insertSpace(USER_B, "SecB");
        tokenA = jwtAccessTokenService.issueAccessToken(USER_A);
        tokenB = jwtAccessTokenService.issueAccessToken(USER_B);
        when(aiProvider.chat(any())).thenReturn(new AiChatResponse(
                "安全回答", "openai-compatible", "test-model", AiUsage.empty()));
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    @Test
    void anonymousCannotAccessAiEndpoints() throws Exception {
        mockMvc.perform(post(BASE + "/conversations", spaceA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/conversations", spaceA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanCreateListAndSend() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE + "/conversations", spaceA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t1\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String body = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long conversationId = extractId(body, "\"id\":");

        mockMvc.perform(get(BASE + "/conversations", spaceA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post(BASE + "/conversations/{id}/messages", spaceA, conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assistantMessage.content").value("安全回答"));
    }

    @Test
    void foreignUserGets404OnOtherSpaceConversation() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE + "/conversations", spaceA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a-only\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andReturn();
        long conversationId = extractId(created.getResponse().getContentAsString(), "\"id\":");

        mockMvc.perform(get(BASE + "/conversations/{id}", spaceB, conversationId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/conversations/{id}/messages", spaceB, conversationId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/conversations/{id}/messages", spaceB, conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hack\"}")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void responseDoesNotExposeApiKeyOrRawProvider() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE + "/conversations", spaceA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andReturn();
        long conversationId = extractId(created.getResponse().getContentAsString(), "\"id\":");

        MvcResult sent = mockMvc.perform(post(BASE + "/conversations/{id}/messages", spaceA, conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"问\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andReturn();
        String body = sent.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("test-api-key"));
        assertFalse(body.contains("Authorization"));
        assertFalse(body.contains("answer_data_json"));
        assertFalse(body.contains("correctOptionKey"));
    }

    @Test
    void sendCannotInjectSystemRoleOrSubject() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE + "/conversations", spaceA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andReturn();
        long conversationId = extractId(created.getResponse().getContentAsString(), "\"id\":");

        // Extra unknown JSON fields must not become SYSTEM/subject.
        mockMvc.perform(post(BASE + "/conversations/{id}/messages", spaceA, conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"ok\",\"role\":\"SYSTEM\",\"userSubject\":\""
                                + USER_B + "\",\"systemPrompt\":\"evil\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated());

        String roles = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(role) FROM ai_message WHERE conversation_id = ?",
                String.class, conversationId);
        org.junit.jupiter.api.Assertions.assertEquals("USER,ASSISTANT", roles);
        Integer other = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE user_subject = ?", Integer.class, USER_B);
        org.junit.jupiter.api.Assertions.assertEquals(0, other);
    }

    private static long extractId(String json, String key) {
        int idx = json.indexOf(key);
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
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

    private void clean() {
        for (String user : new String[]{USER_A, USER_B}) {
            jdbc.update("DELETE FROM ai_message WHERE conversation_id IN "
                    + "(SELECT id FROM ai_conversation WHERE user_subject=?)", user);
            jdbc.update("DELETE FROM ai_conversation WHERE user_subject=?", user);
            jdbc.update("DELETE FROM learning_space WHERE owner_subject=?", user);
        }
    }

    private void assertSchema() {
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("expected " + EXPECTED_SCHEMA + " got " + actual);
        }
    }
}
