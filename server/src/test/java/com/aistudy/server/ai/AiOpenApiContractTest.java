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

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageReferenceMapper aiMessageReferenceMapper;

    @MockitoBean
    private com.aistudy.server.ai.settings.AiProviderSettingsMapper aiProviderSettingsMapper;

    @MockitoBean
    private com.aistudy.server.ai.settings.AiProviderSecretMapper aiProviderSecretMapper;

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

    /**
     * C03 — send-message accepts context IDs (never body text), so the client can
     * open the tutor from a Source / page / block / knowledge point.
     */
    @Test
    void sendMessageRequestCarriesOptionalContextIds() throws Exception {
        for (String field : new String[]{"sourceId", "sourcePageId", "contentBlockId", "knowledgePointId"}) {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.components.schemas.SendMessageRequest.properties." + field + ".type")
                            .value("integer"));
        }
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.SendMessageRequest.properties.content").exists())
                // IDs only: no body-text field may be smuggled into the request
                .andExpect(jsonPath(
                        "$.components.schemas.SendMessageRequest.properties.sourceText").doesNotExist())
                .andExpect(jsonPath(
                        "$.components.schemas.SendMessageRequest.properties.blockText").doesNotExist());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths["
                        + "'/api/v1/spaces/{spaceId}/ai/conversations/{conversationId}/messages']"
                        + ".post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/SendMessageRequest"));
    }

    /** C04 — every citation carries the structured route, not only a label. */
    @Test
    void contextReferenceCarriesStructuredLocatorIds() throws Exception {
        for (String field : new String[]{"type", "id", "title", "snippet", "locator",
                "sourceId", "sourcePageId", "contentBlockId"}) {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.components.schemas.ContextReference.properties." + field).exists());
        }
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.ContextReference.properties.locator.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.ContextReference.properties.contentBlockId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.paths["
                        + "'/api/v1/spaces/{spaceId}/ai/conversations/{conversationId}/messages']"
                        + ".get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/MessagePageResponse"))
                .andExpect(jsonPath("$.components.schemas.MessagePageResponse.properties.content"
                        + ".items.$ref").value("#/components/schemas/MessageView"))
                .andExpect(jsonPath("$.components.schemas.MessageView.properties.references"
                        + ".items.$ref").value("#/components/schemas/ContextReference"));
    }

    /**
     * W11 — both AI list endpoints are paginated through query parameters, so the
     * generated client exposes page/size instead of fetching an unbounded list.
     */
    @Test
    void aiListEndpointsDeclarePaginationParameters() throws Exception {
        String conversations = "$.paths['/api/v1/spaces/{spaceId}/ai/conversations'].get.parameters";
        String messages = "$.paths['/api/v1/spaces/{spaceId}/ai/conversations"
                + "/{conversationId}/messages'].get.parameters";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(conversations + "[?(@.name=='page')]").exists())
                .andExpect(jsonPath(conversations + "[?(@.name=='size')]").exists())
                .andExpect(jsonPath(conversations + "[?(@.name=='page')].in").value("query"))
                .andExpect(jsonPath(conversations + "[?(@.name=='page')].schema.type").value("integer"))
                .andExpect(jsonPath(messages + "[?(@.name=='page')]").exists())
                .andExpect(jsonPath(messages + "[?(@.name=='size')]").exists())
                .andExpect(jsonPath(messages + "[?(@.name=='size')].in").value("query"));
    }

    /** The list envelope stays the Spring-style one the desktop client reads today. */
    @Test
    void aiListResponsesKeepContentAndTotalElementsFields() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ConversationPageResponse.properties.content")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ConversationPageResponse.properties.totalElements")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.MessagePageResponse.properties.content").exists())
                .andExpect(jsonPath("$.components.schemas.MessagePageResponse.properties.totalElements")
                        .exists());
    }

    @Test
    void aiSettingsPathsAreExposedWithoutApiKeyInGetSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/settings/ai']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/settings/ai/test-connection']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/settings/ai/api-key']").exists());
        String docs = mockMvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
                docs.contains("apiKeyConfigured"),
                "OpenAPI must document apiKeyConfigured");
        org.junit.jupiter.api.Assertions.assertFalse(
                docs.contains("\"apiKey\":{\"type\":\"string\"}"),
                "GET response must not expose apiKey as a readable string field");
    }

    @Test
    void aiEndpointsRequireBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/spaces/{spaceId}/ai/conversations'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/spaces/{spaceId}/ai/conversations/{conversationId}/messages']"
                                + ".post.security[0].bearerAuth").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/settings/ai'].get.security[0].bearerAuth").exists());
    }
}
