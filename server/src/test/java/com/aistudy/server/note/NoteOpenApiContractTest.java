package com.aistudy.server.note;

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
 * Notes contract (C-01) — the generated OpenAPI document must offer all five
 * verbs with typed request/response schemas, because the Desktop client is
 * generated from it and must not hand-write DTOs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MapperMockRegistrar.class)
class NoteOpenApiContractTest {

    private static final String NOTES = "/api/v1/spaces/{spaceId}/notes";
    private static final String NOTE = "/api/v1/spaces/{spaceId}/notes/{noteId}";

    @Autowired
    private MockMvc mockMvc;

    /** Not a {@code @Mapper}: constructor-injects JdbcTemplate. */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /** (1) list / create / read / replace / delete are all in the contract. */
    @Test
    void allFiveVerbsAreExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + NOTES + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + NOTES + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + NOTE + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + NOTE + "'].put").exists())
                .andExpect(jsonPath("$.paths['" + NOTE + "'].delete").exists());
    }

    /** (2) the list takes page/size/keyword and returns the shared envelope. */
    @Test
    void listIsPagedWithKeyword() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + NOTES + "'].get.parameters[?(@.name=='page')]").exists())
                .andExpect(jsonPath("$.paths['" + NOTES + "'].get.parameters[?(@.name=='size')]").exists())
                .andExpect(jsonPath("$.paths['" + NOTES + "'].get.parameters[?(@.name=='keyword')]").exists())
                .andExpect(jsonPath("$.paths['" + NOTES + "'].get.responses['200']"
                        + ".content['application/json'].schema.$ref")
                        .value("#/components/schemas/NotePageResponse"))
                .andExpect(jsonPath("$.components.schemas.NotePageResponse.properties.items"
                        + ".items.$ref").value("#/components/schemas/NoteView"))
                .andExpect(jsonPath("$.components.schemas.NotePageResponse.properties.totalItems.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.NotePageResponse.properties.pageSize.type")
                        .value("integer"));
    }

    /** (3) NoteView carries the context pointers the client navigates on. */
    @Test
    void noteViewCarriesContextPointers() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
        for (String field : new String[]{"id", "spaceId", "title", "content", "contentFormat",
                "sourceId", "sourcePageId", "contentBlockId", "knowledgePointId", "questionId",
                "createdAt", "updatedAt"}) {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(jsonPath("$.components.schemas.NoteView.properties." + field).exists());
        }
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.NoteView.properties.sourceId.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.NoteView.properties.createdAt.type").value("string"))
                // ownership stays server-side
                .andExpect(jsonPath("$.components.schemas.NoteView.properties.userSubject").doesNotExist());
    }

    /** (4) create and update take typed bodies; only create is documented as 201. */
    @Test
    void writeVerbsTakeTypedBodies() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + NOTES + "'].post.requestBody.content['application/json']"
                        + ".schema.$ref").value("#/components/schemas/NoteCreateRequest"))
                .andExpect(jsonPath("$.paths['" + NOTES + "'].post.responses['201']"
                        + ".content['application/json'].schema.$ref")
                        .value("#/components/schemas/NoteView"))
                .andExpect(jsonPath("$.paths['" + NOTE + "'].put.requestBody.content['application/json']"
                        + ".schema.$ref").value("#/components/schemas/NoteUpdateRequest"))
                .andExpect(jsonPath("$.paths['" + NOTE + "'].put.responses['200']"
                        + ".content['application/json'].schema.$ref")
                        .value("#/components/schemas/NoteView"))
                .andExpect(jsonPath("$.paths['" + NOTE + "'].delete.responses['204']").exists());
    }

    /** (5) every note verb requires the bearer token. */
    @Test
    void notePathsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + NOTES + "'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + NOTE + "'].put.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + NOTE + "'].delete.security[0].bearerAuth").exists());
    }
}
