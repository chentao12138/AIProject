package com.aistudy.server.search;

import com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository;
import com.aistudy.server.testsupport.MapperMockRegistrar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Search contract (typed results) — the Desktop client is generated from the
 * OpenAPI document, so the result type list and the {@code noteId} routing
 * pointer have to be part of the schema, not folklore.
 *
 * <p>The five pre-existing type strings are pinned by the generated client, so
 * this test asserts the full set rather than only the new member.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MapperMockRegistrar.class)
class SearchOpenApiContractTest {

    private static final String SEARCH_PATH = "/api/v1/spaces/{spaceId}/search";

    private static final List<String> RESULT_TYPES = List.of(
            "SOURCE", "CONTENT_BLOCK", "KNOWLEDGE_POINT", "QUESTION", "WRONG_QUESTION", "NOTE");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Not a {@code @Mapper}: constructor-injects JdbcTemplate. */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /** (1) {@code type} is an enum over the real type list, NOTE included. */
    @Test
    void resultTypeIsAnEnumThatIncludesNote() throws Exception {
        JsonNode type = resultSchema().path("properties").path("type");
        assertEquals("string", type.path("type").asText(), "type must stay a string enum: " + type);
        List<String> emitted = textValues(type.path("enum"));
        assertThat(emitted).containsExactlyInAnyOrderElementsOf(RESULT_TYPES);
        assertThat(emitted).contains("SOURCE", "CONTENT_BLOCK", "KNOWLEDGE_POINT",
                "QUESTION", "WRONG_QUESTION", "NOTE");
    }

    /** (2) noteId is on the result schema and nullable, like the other pointers. */
    @Test
    void resultSchemaCarriesNullableNoteId() throws Exception {
        JsonNode noteId = resultSchema().path("properties").path("noteId");
        assertFalse(noteId.isMissingNode(), "SearchResult.noteId is missing from the contract");
        assertEquals("integer", noteId.path("type").asText());
        assertEquals("int64", noteId.path("format").asText());
        assertThat(textValues(resultSchema().path("required"))).doesNotContain("noteId");
        // the generic id the other types use is untouched
        assertEquals("int64", resultSchema().path("properties").path("id").path("format").asText());
    }

    /** (3) the types filter advertises NOTE, so the client can ask for it. */
    @Test
    void typesFilterAdvertisesNote() throws Exception {
        JsonNode operation = document().path("paths").path(SEARCH_PATH).path("get");
        assertFalse(operation.isMissingNode(), "search operation absent from the contract");
        JsonNode types = null;
        for (JsonNode parameter : operation.path("parameters")) {
            if ("types".equals(parameter.path("name").asText())) {
                types = parameter;
            }
        }
        assertTrue(types != null, "no types parameter on the search operation");
        assertThat(types.path("description").asText()).contains("NOTE");
    }

    private JsonNode resultSchema() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");
        JsonNode result = schemas.path("SearchResult");
        assertFalse(result.isMissingNode(), "SearchResult schema absent from the contract");
        return result;
    }

    private JsonNode document() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
