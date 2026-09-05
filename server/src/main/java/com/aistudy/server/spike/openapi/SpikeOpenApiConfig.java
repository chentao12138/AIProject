package com.aistudy.server.spike.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SPIKE-005 MICRO-02A — OpenAPI Contract security metadata.
 *
 * <p>Purpose: declare the {@code bearerAuth} HTTP Bearer (JWT) security
 * scheme so that the JSON produced by springdoc at
 * {@code /v3/api-docs} records how clients are expected to authenticate
 * against this project's protected endpoints.
 *
 * <h3>What this class DOES</h3>
 *
 * <p>It defines exactly one bean: an {@link OpenAPI} instance whose
 * {@code components.securitySchemes} map contains one entry:
 *
 * <pre>
 * components:
 *   securitySchemes:
 *     bearerAuth:
 *       type: http
 *       scheme: bearer
 *       bearerFormat: JWT
 * </pre>
 *
 * <p>That entry is what lets controllers marked with
 * {@code @SecurityRequirement(name = "bearerAuth")} be reflected in the
 * generated contract as:
 *
 * <pre>
 * security:
 *   - bearerAuth: []
 * </pre>
 *
 * <h3>What this class deliberately DOES NOT DO</h3>
 *
 * <p>Deliberate non-goals of this MICRO, deferred to later work:
 *
 * <ul>
 *   <li>No global {@code openAPI.addSecurityItem(...)}. Adding a
 *       document-wide {@code security} entry would incorrectly mark
 *       {@code /health} and {@code /v3/api-docs} as requiring bearerAuth,
 *       when both are anonymous endpoints. Every security requirement in
 *       this project is therefore declared <em>per-controller</em> via
 *       the SpringDoc {@code @SecurityRequirement} annotation on
 *       {@link com.aistudy.server.spike.auth.SpikeProtectedController}.
 *   </li>
 *   <li>No {@code title}, {@code version}, {@code info}, {@code tags},
 *       {@code servers}, {@code externalDocs}, or {@code license}. Those
 *       will be added only if a later MICRO or ADR requires them.
 *       springdoc fills sensible defaults when they are absent.
 *   </li>
 *   <li>No real secret, no issuer, no access-token example, no
 *       JWT signing-key description. SPIKE-004 deliberately uses a
 *       JVM-lifetime random {@code SecretKey}
 *       ({@link com.aistudy.server.spike.auth.SpikeSecurityConfig});
 *       baking that fact into the contract would leak an implementation
 *       detail into a client-facing document. The contract only records
 *       the authentication <em>mechanism</em> (HTTP Bearer + JWT format),
 *       not the signing scheme, key length, or key source.
 *   </li>
 *   <li>No change to the runtime SecurityFilterChain. This bean is pure
 *       documentation metadata; the actual 401/403 behavior still comes
 *       from {@link com.aistudy.server.spike.auth.SpikeSecurityConfig}.
 *   </li>
 * </ul>
 *
 * <h3>Why HTTP Bearer + JWT (not APIKey, not OAuth2)</h3>
 *
 * <p>The OpenAPI 3.x {@code type: http} + {@code scheme: bearer}
 * + {@code bearerFormat: JWT} combination is the standard way to declare
 * the same "Authorization: Bearer &lt;JWT&gt;" pattern used by
 * SPIKE-004's {@code spring-boot-starter-oauth2-resource-server}.
 * {@code type: oauth2} would imply an OAuth2 authorization code / client
 * credentials / implicit flow with a real authorization server, which
 * SPIKE-004 does not have. {@code type: apiKey} would hide the fact that
 * the scheme is standard HTTP Bearer. {@code http+bearer} is the honest
 * description of what the resource server actually verifies.
 */
@Configuration
public class SpikeOpenApiConfig {

    /**
     * SPIKE-005 MICRO-02A: OpenAPI document bean that declares the
     * {@code bearerAuth} security scheme used by protected SPIKE
     * endpoints.
     *
     * <p>springdoc picks up this bean automatically and merges it into
     * the JSON served at {@code /v3/api-docs} under
     * {@code components.securitySchemes.bearerAuth}. No
     * {@code openAPI.addSecurityItem(...)} call is made here, so the
     * top-level {@code security} array on the generated document stays
     * empty and anonymous endpoints keep an anonymous contract.
     *
     * <p>Named {@code spikeOpenApi} to distinguish it from any future
     * production {@code OpenAPI} bean and to make "this is SPIKE-only
     * metadata" obvious at the source level.
     */
    @Bean
    public OpenAPI spikeOpenApi() {
        OpenAPI openAPI = new OpenAPI();
        openAPI.components(
                new Components()
                        .addSecuritySchemes("bearerAuth", bearerAuthScheme())
        );
        return openAPI;
    }

    /**
     * The {@code bearerAuth} security scheme entry.
     *
     * <p>Kept as a small factory method (rather than an inline
     * initializer) so that:
     * <ol>
     *   <li>the intent — "HTTP Bearer with JWT format" — is captured by
     *       a named method,</li>
     *   <li>later MICROs can reuse the same scheme builder when
     *       declaring a per-operation {@code SecurityRequirement}
     *       without duplicating the four-field setup,</li>
     *   <li>a future ADR that replaces this with {@code type: oauth2}
     *       or an asymmetric-key flow has a single place to change.</li>
     * </ol>
     *
     * <p>The three fields are the exact minimal representation of the
     * mechanism used by SPIKE-004:
     *
     * <ul>
     *   <li>{@code type = http}: standard HTTP authentication (not
     *       API-key, not OAuth2).</li>
     *   <li>{@code scheme = bearer}: the value goes into the
     *       {@code Authorization: Bearer *** header.</li>
     *   <li>{@code bearerFormat = JWT}: the bearer token is a JSON
     *       Web Token. springdoc writes this field as
     *       {@code bearerFormat: JWT} in the JSON output; code
     *       generators use it to pick a JWT-aware client instead of a
     *       generic string-token client.</li>
     * </ul>
     */
    private SecurityScheme bearerAuthScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");
    }
}
