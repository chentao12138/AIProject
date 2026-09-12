package com.aistudy.server.ai;

import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.testsupport.TestProfileMapperMocks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI-004 — OpenAPI contract for AI tutor endpoints (lightweight profile).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestProfileMapperMocks.class)
class AiOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiProvider aiProvider;


    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountRoleMapper userAccountRoleMapper;

    @MockitoBean
    private com.aistudy.server.auth.mapper.RefreshSessionMapper refreshSessionMapper;

    @MockitoBean
    private com.aistudy.server.search.mapper.SearchMapper searchMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiConversationMapper aiConversationMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageMapper aiMessageMapper;

    @Test
    void aiConversationPathsAreExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/ai/conversations']").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/spaces/{spaceId}/ai/conversations/{conversationId}']").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/spaces/{spaceId}/ai/conversations/{conversationId}/archive']").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/spaces/{spaceId}/ai/conversations/{conversationId}/messages']").exists());
    }

    @Test
    void conversationAndMessageSchemasExist() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas").exists())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
                docs.contains("ConversationView"),
                "OpenAPI must document ConversationView");
        org.junit.jupiter.api.Assertions.assertTrue(
                docs.contains("SendMessageRequest"),
                "OpenAPI must document SendMessageRequest");
        org.junit.jupiter.api.Assertions.assertFalse(docs.contains("answer_data_json"));
    }

    @Test
    void aiEndpointsRequireBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/spaces/{spaceId}/ai/conversations'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/spaces/{spaceId}/ai/conversations/{conversationId}/messages']"
                                + ".post.security[0].bearerAuth").exists());
    }
}
