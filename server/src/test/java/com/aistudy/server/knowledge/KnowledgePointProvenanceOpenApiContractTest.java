package com.aistudy.server.knowledge;

import com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository;
import com.aistudy.server.testsupport.MapperMockRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-007 — OpenAPI contract test for the KnowledgePointSource
 * provenance API ({@code test} profile + {@link MapperMockRegistrar}
 * so the context boots without MySQL).
 *
 * <p>Verifies: both provenance paths are exposed; POST has a typed
 * {@code LinkKnowledgePointSourcesRequest} body (contentBlockIds
 * array, required) and returns {@code array + items.$ref} of
 * {@code KnowledgePointSourceResponse}; GET returns the same typed
 * list; the response schema carries the citation identity plus the
 * {@code sourceId} / {@code sourcePageId} the client navigates on, and
 * nothing else (no normalizedText / storageKey / block content);
 * bearerAuth is declared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MapperMockRegistrar.class)
class KnowledgePointProvenanceOpenApiContractTest {

    private static final String LINK_BASE =
            "/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/sources";

    private static final String RESPONSE_SCHEMA =
            "$.components.schemas.KnowledgePointSourceResponse.properties.";

    @Autowired
    private MockMvc mockMvc;

    /** Not a {@code @Mapper}: constructor-injects JdbcTemplate. */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /** (1) both provenance paths are exposed. */
    @Test
    void provenancePathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].get").exists());
    }

    /** (2) link POST body is the typed request with required contentBlockIds array. */
    @Test
    void linkPostHasTypedRequestBody() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/LinkKnowledgePointSourcesRequest"))
                .andExpect(jsonPath("$.components.schemas.LinkKnowledgePointSourcesRequest.required[0]")
                        .value("contentBlockIds"))
                .andExpect(jsonPath("$.components.schemas.LinkKnowledgePointSourcesRequest.properties.contentBlockIds.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.LinkKnowledgePointSourcesRequest.properties.contentBlockIds.items.type")
                        .value("integer"));
    }

    /** (3) link POST 201 → array + items.$ref of KnowledgePointSourceResponse. */
    @Test
    void linkPostReturnsArrayOfKnowledgePointSourceResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post.responses['201'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post.responses['201'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/KnowledgePointSourceResponse"));
    }

    /** (4) list GET → array + items.$ref. */
    @Test
    void listGetReturnsArrayOfKnowledgePointSourceResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/KnowledgePointSourceResponse"));
    }

    /** (5) response schema exposes citation identity only. */
    @Test
    void responseSchemaExposesCitationIdentityOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(RESPONSE_SCHEMA + "knowledgePointId.type")
                        .value("integer"))
                .andExpect(jsonPath(RESPONSE_SCHEMA + "contentBlockId.type")
                        .value("integer"))
                .andExpect(jsonPath(RESPONSE_SCHEMA + "relationType.type")
                        .value("string"))
                .andExpect(jsonPath(RESPONSE_SCHEMA + "relevanceScore.type")
                        .value("number"))
                .andExpect(jsonPath(RESPONSE_SCHEMA + "normalizedText")
                        .doesNotExist())
                .andExpect(jsonPath(RESPONSE_SCHEMA + "storageKey")
                        .doesNotExist());
    }

    /** (6) both paths declare bearerAuth. */
    @Test
    void bothPathsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].get.security[0].bearerAuth")
                        .exists());
    }

    /**
     * (7) the provenance entry has to be clickable: the response carries the
     * source and the source page of the cited block, which is what the
     * generated Desktop client opens. Pinning the names here means a rename
     * fails the build instead of silently shipping an unclickable citation.
     */
    @Test
    void responseSchemaCarriesProvenanceNavigationIds() throws Exception {
        for (String field : new String[]{"sourceId", "sourcePageId"}) {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(RESPONSE_SCHEMA + field).exists())
                    .andExpect(jsonPath(RESPONSE_SCHEMA + field + ".type").value("integer"));
        }
    }
}
