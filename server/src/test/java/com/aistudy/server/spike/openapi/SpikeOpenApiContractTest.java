package com.aistudy.server.spike.openapi;

import com.aistudy.server.space.mapper.LearningSpaceMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPIKE-005 MICRO-02A + MICRO-03A — OpenAPI Contract test.
 *
 * <p>MICRO-02A covers the security-metadata triplet:
 *
 * <ol>
 *   <li>A {@code components.securitySchemes.bearerAuth} entry with the
 *       exact triplet:
 *       {@code type = "http"}, {@code scheme = "bearer"},
 *       {@code bearerFormat = "JWT"}. This proves
 *       {@link SpikeOpenApiConfig#spikeOpenApi()} reached springdoc's
 *       contract builder and was emitted into the JSON output.
 *   </li>
 *   <li>A per-operation {@code security: [{bearerAuth: []}]} entry on
 *       {@code /api/v1/spike/protected}, proving the class-level
 *       {@code @SecurityRequirement(name = "bearerAuth")} on
 *       {@link com.aistudy.server.spike.auth.SpikeProtectedController}
 *       is picked up by springdoc.
 *   </li>
 *   <li>The absence of any {@code security} block on {@code /health},
 *       proving the anonymous {@code /health} endpoint keeps an
 *       anonymous contract. This is the assertion that guards the
 *       "no global {@code addSecurityItem(...)}" rule in
 *       {@link SpikeOpenApiConfig}: if someone added a document-wide
 *       {@code security} entry, springdoc would inherit it onto every
 *       operation and this assertion would fail.
 *   </li>
 * </ol>
 *
 * <p>MICRO-03A covers typed response schemas:
 *
 * <ol start="4">
 *   <li>{@code components.schemas.SpikeHealthResponse} has three
 *       string properties: {@code status}, {@code service}, {@code phase}.
 *       This proves the Map return type on {@code SpikeHealthController}
 *       was replaced with the record type and springdoc emitted a real
 *       schema rather than the generic
 *       {@code additionalProperties: {type: string}} shape.
 *   </li>
 *   <li>{@code components.schemas.SpikeStatusResponse} has a single
 *       {@code status} property, and
 *       {@code /api/v1/spike/protected} references it via {@code $ref}.
 *       The same component is shared by
 *       {@code /api/v1/spike/method-denied}.
 *   </li>
 *   <li>{@code components.schemas.SpikeSpaceAuthorizationResponse} has
 *       two properties: {@code spaceId} and {@code status}.
 *   </li>
 * </ol>
 *
 * <p>Assertions are scoped to the security metadata triplet and to the
 * presence/shape of the typed schemas under
 * {@code components.schemas}. We deliberately do NOT assert the full
 * OpenAPI document — operationIds, tags, servers, license, info, and
 * the complete response wrapper chain are left to a later MICRO to
 * lock down.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpikeOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * MICRO-07D-B / MICRO-08A / MICRO-08B pattern: mock the SPIKE-only
     * membership repository so this test's Spring context boots without
     * a real {@code JdbcTemplate} / {@code DataSource} bean. This test
     * only cares about the OpenAPI contract JSON — it does NOT exercise
     * the membership repository. The mock exists purely to keep the
     * shared {@code test} profile free of database wiring.
     *
     * Deliberately NOT stubbed: this test does not assert anything about
     * {@code hasActiveMembership}, and springdoc's contract generation
     * does not call the repository. Any {@code when(...)} call here
     * would be dead code.
     */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /**
     * BUSINESS-001: mock the production LearningSpace mapper so this
     * OpenAPI contract test keeps running without MyBatis-Plus /
     * DataSource under the {@code test} profile. Not stubbed —
     * springdoc's contract generation does not call the mapper.
     * The real mapper's schema contribution to /v3/api-docs is
     * verified by {@code LearningSpaceOpenApiContractTest} (also
     * test profile, same mock policy).
     */
    @MockitoBean
    private LearningSpaceMapper learningSpaceMapper;
    /**
     * BUSINESS-002: mock the production Source mapper so this
     * full-context test keeps running without MyBatis-Plus /
     * DataSource under this profile. Not stubbed — this test never
     * touches Source persistence. The real SourceMapper is exercised
     * by SourceVerticalSliceIntegrationTest (flyway-it profile).
     */
    @MockitoBean
    private SourceMapper sourceMapper;


    /**
     * MICRO-02A assertion 1: the {@code bearerAuth} security scheme
     * entry must exist in {@code components.securitySchemes} with the
     * exact triplet that matches SPIKE-004's runtime:
     * HTTP Bearer + JWT.
     *
     * Asserting the full triplet here — not just one field — proves
     * the bean is not only wired but configured correctly. If a future
     * edit accidentally flipped {@code scheme = "bearer"} to {@code "apiKey"}
     * or dropped {@code bearerFormat}, this test would fail.
     */
    @Test
    void bearerAuthSecuritySchemeIsDeclared() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type")
                        .value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat")
                        .value("JWT"));
    }

    /**
     * MICRO-02A assertion 2: {@code GET /api/v1/spike/protected} must
     * carry a per-operation {@code security} block that references
     * {@code bearerAuth} with an empty value array.
     *
     * The empty array is the OpenAPI 3.x convention for HTTP Bearer
     * (there is no "scope" concept as there is in OAuth2); the value
     * array carries authorization scopes for OAuth2 flows only.
     *
     * We assert on one representative protected endpoint rather than
     * all three (protected, method-denied, spaces/{spaceId}) because
     * the class-level {@code @SecurityRequirement} on
     * {@link com.aistudy.server.spike.auth.SpikeProtectedController}
     * is a single declaration springdoc applies uniformly. Testing one
     * endpoint proves the annotation was picked up; asserting all three
     * would just repeat the same assertion three times without adding
     * coverage.
     *
     * We also assert that {@code $.security} exists at that endpoint
     * as an array with at least one element — this catches the
     * "annotation silently dropped, {@code security} absent" failure
     * mode as well as the "wrong scheme name referenced" failure mode.
     */
    @Test
    void protectedEndpointCarriesBearerAuthSecurity() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spike/protected'].get.security")
                        .isArray())
                .andExpect(jsonPath("$.paths['/api/v1/spike/protected'].get.security[0].bearerAuth")
                        .isArray());
    }

    /**
     * MICRO-02A assertion 3: {@code GET /health} must NOT carry a
     * {@code security} block. This is the inverse of assertion 2 — it
     * proves the "no global security" rule in
     * {@link SpikeOpenApiConfig}: since we did not call
     * {@code openAPI.addSecurityItem(...)}, springdoc does not inherit
     * a document-wide {@code security} requirement onto the anonymous
     * {@code /health} endpoint.
     *
     * This is the guard against a subtle regression where someone adds
     * a global {@code security} entry to the {@code OpenAPI} bean "to
     * simplify" the metadata, and in doing so incorrectly marks
     * anonymous endpoints as requiring bearerAuth. Any client generator
     * consuming that (incorrect) contract would require Bearer auth for
     * {@code /health}, which is wrong.
     *
     * We assert {@code $.paths['/health'].get.security} does not exist
     * (using the {@code doesNotExist()} matcher) rather than checking
     * for an empty array, because springdoc will only emit the
     * {@code security} field if there is something to emit — no global
     * security means no field at all.
     */
    @Test
    void healthEndpointHasNoBearerAuthSecurity() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/health'].get.security").doesNotExist());
    }

    // ==================================================================
    // SPIKE-005 MICRO-03A — typed response schema assertions
    // ==================================================================

    /**
     * MICRO-03A assertion 4: {@code GET /health} must now emit a typed
     * response schema with three explicit properties
     * ({@code status}, {@code service}, {@code phase}) — not the
     * pre-MICRO-03A generic
     * {@code {type: object, additionalProperties: {type: string}}}
     * schema that a Map return would produce.
     *
     * <p>springdoc resolves a Java record's return type via its Jackson
     * integration, which:
     * <ul>
     *   <li>emits the record schema under
     *       {@code components.schemas.SpikeHealthResponse},</li>
     *   <li>references it from
     *       {@code paths./health.get.responses.200.content.
     *       application/json.schema} via {@code $ref:
     *       '#/components/schemas/SpikeHealthResponse'}, and</li>
     *   <li>populates {@code type: object, required: [status, service,
     *       phase], properties: {status: {type: string}, service:
     *       {type: string}, phase: {type: string}}} in the component.</li>
     * </ul>
     *
     * <p>We assert on the {@code components.schemas} entry directly
     * rather than unwrapping the {@code $ref} from the response schema.
     * Asserting the component directly is equivalent (the component is
     * the only place the properties are defined; the response's schema
     * is a pure reference) and does not depend on whether springdoc
     * chooses inline or {@code $ref} emission for the response wrapper.
     */
    @Test
    void healthResponseHasTypedSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.SpikeHealthResponse.properties.status.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SpikeHealthResponse.properties.service.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SpikeHealthResponse.properties.phase.type")
                        .value("string"));
    }

    /**
     * MICRO-03A assertion 5: {@code GET /api/v1/spike/protected} must
     * now emit a typed response schema for the 200 response with a
     * single explicit property: {@code status}. This is the same record
     * type as {@code /api/v1/spike/method-denied}, so
     * {@code components.schemas.SpikeStatusResponse} is shared.
     *
     * <p>We assert on the component once rather than per-endpoint because
     * both endpoints reference the same schema; asserting the component
     * directly is equivalent to asserting the response shape on both
     * endpoints.
     */
    @Test
    void protectedResponseHasTypedStatusSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.SpikeStatusResponse.properties.status.type")
                        .value("string"))
                .andExpect(jsonPath("$.paths['/api/v1/spike/protected'].get.responses.200.content['application/json'].schema.$ref")
                        .value("#/components/schemas/SpikeStatusResponse"));
    }

    /**
     * MICRO-03A assertion 6: {@code GET /api/v1/spike/spaces/{spaceId}}
     * must now emit a typed response schema for the 200 response with
     * two explicit properties: {@code spaceId} and {@code status}. Both
     * are {@code type: string}.
     *
     * <p>This is the endpoint where the strongest typed-schema benefit
     * is visible: a Map return would produce an untyped dict shape; a
     * record return produces a real interface a TS code generator can
     * render as {@code interface SpikeSpaceAuthorizationResponse {
     * spaceId: string; status: string; }}.
     */
    @Test
    void spaceAuthorizationResponseHasTypedSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.SpikeSpaceAuthorizationResponse.properties.spaceId.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SpikeSpaceAuthorizationResponse.properties.status.type")
                        .value("string"));
    }
}
