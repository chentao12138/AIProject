package com.aistudy.server.spike.auth;

import com.aistudy.server.spike.openapi.model.SpikeSpaceAuthorizationResponse;
import com.aistudy.server.spike.openapi.model.SpikeStatusResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPIKE-004 MICRO-02 + MICRO-06A — temporary protected endpoints used ONLY
 * to prove that Spring Security enforces both an authentication boundary
 * and a method-level authorization layer.
 *
 * This is a SPIKE-only controller. It is NOT a business API and MUST NOT be
 * reused or promoted to production. Once a real auth boundary is in place
 * (JWT / OAuth2 / UserDetailsService / Space authorization / ...), this
 * controller can be deleted along with the corresponding assertions in
 * {@link SpikeSecurityBoundaryTest}.
 *
 * Endpoints:
 *   - {@code GET /api/v1/spike/protected}
 *     MICRO-02 gate. Only {@code authenticated()} HTTP-level authorization
 *     is applied (via {@code SecurityFilterChain}). Anonymous → 401; valid
 *     Bearer → 200.
 *
 *   - {@code GET /api/v1/spike/method-denied}
 *     MICRO-06A gate. HTTP-level authentication still has to pass first
 *     (so this endpoint is unreachable from an anonymous caller and would
 *     return 401 there); a caller that authenticates successfully then hits
 *     the {@code @PreAuthorize("denyAll()")} expression, which produces a
 *     method-level authorization failure and MUST yield 403. This isolates
 *     the 401 (authentication) vs 403 (authorization) contract before
 *     real Space / membership rules are introduced.
 *
 * The response bodies are deliberately trivial. The point of these endpoints
 * is not to serve data; it is to give the boundary tests concrete targets
 * that, when they fail, expose exactly which security layer rejected the
 * request.
 *
 * Do NOT add any business logic here (that is not this controller's job).
 *
 * <h3>SPIKE-005 MICRO-02A: OpenAPI security metadata</h3>
 *
 * The {@code @SecurityRequirement(name = "bearerAuth")} annotation on this
 * class is a documentation-only declaration consumed by springdoc when it
 * generates the JSON contract at {@code /v3/api-docs}. It produces, for
 * every operation under this controller, a per-operation entry:
 *
 * <pre>
 * security:
 *   - bearerAuth: []
 * </pre>
 *
 * The {@code bearerAuth} scheme itself is defined by
 * {@link com.aistudy.server.spike.openapi.SpikeOpenApiConfig} in
 * {@code components.securitySchemes}. The scheme is HTTP Bearer (JWT),
 * matching the runtime behavior enforced by
 * {@link SpikeSecurityConfig}:
 * {@code Authorization: Bearer <JWT>} on any of the three endpoints in
 * this controller.
 *
 * The annotation is placed at the class level (rather than per-method)
 * because all three SPIKE endpoints currently require Bearer authentication
 * at the HTTP level — the {@code @PreAuthorize} annotations on individual
 * methods layer authorization on top of that, but the authentication
 * requirement is uniform across the controller. Placing it per-method
 * would repeat the same declaration three times with no differentiating
 * value.
 *
 * This annotation does NOT change runtime authorization behavior: the
 * actual 401 / 403 enforcement still comes from the SecurityFilterChain
 * and {@code @PreAuthorize} SpEL expressions. The annotation only makes
 * the OpenAPI contract reflect what the runtime already requires, so a
 * client generator can wire Bearer auth into the generated client.
 *
 * No {@code @Operation} / {@code @ApiResponse} / {@code @Schema}
 * annotations are added on this controller or its methods — the
 * springdoc contract is derived from the Java signatures alone.
 *
 * <h3>SPIKE-005 MICRO-03A: typed responses</h3>
 *
 * The three endpoint methods were previously declared as
 * {@code Map<String, String>}, which forced springdoc to emit a generic
 * {@code {type: object, additionalProperties: {type: string}}} schema
 * for every response — a shape a TypeScript code generator can only
 * turn into a bare {@code Record<string, string>} client type. MICRO-03A
 * replaces those returns with explicit SPIKE-only records:
 *
 * <ul>
 *   <li>{@code protectedEndpoint()} →
 *       {@link com.aistudy.server.spike.openapi.model.SpikeStatusResponse}
 *       (single field: {@code status}).</li>
 *   <li>{@code methodDeniedEndpoint()} →
 *       {@link com.aistudy.server.spike.openapi.model.SpikeStatusResponse}
 *       (same record, same field; the record is reused across the two
 *       endpoints that share the same single-string shape).</li>
 *   <li>{@code spaceEndpoint()} →
 *       {@link com.aistudy.server.spike.openapi.model.SpikeSpaceAuthorizationResponse}
 *       (two fields: {@code spaceId}, {@code status}).</li>
 * </ul>
 *
 * The JSON keys and values are preserved verbatim from the previous
 * Map contract; only the Java return type and the corresponding
 * OpenAPI schema shape change. The {@code @GetMapping},
 * {@code @RequestMapping}, {@code @PreAuthorize}, and
 * {@code @SecurityRequirement} annotations are untouched.
 *
 * The SPIKE-only records live under
 * {@code com.aistudy.server.spike.openapi.model}, not in a shared
 * package, so they cannot be accidentally promoted to production DTOs
 * and will be deleted along with this controller when the SPIKE ends.
 *
 * <h3>SPIKE-005 FINALIZE-01: explicit produces</h3>
 *
 * Each {@code @GetMapping} on this controller now declares
 * {@code produces = MediaType.APPLICATION_JSON_VALUE}. Without that,
 * springdoc's default is {@code *&#47;*} in the OpenAPI contract's
 * {@code responses.<status>.content} map key, which makes the response
 * content type ambiguous and forces downstream TypeScript generators to
 * guess. Pinning {@code application/json} produces the deterministic
 * {@code content['application/json'].schema} shape that a code
 * generator can key off reliably.
 *
 * The wire-level {@code Content-Type} header the servlet writes was
 * already {@code application/json} (Jackson always serializes the
 * record to JSON); this declaration is contract-only, it does not
 * change response bodies, HTTP status codes, or the security behavior
 * of the three endpoints.
 */
@RestController
@RequestMapping("/api/v1/spike")
@SecurityRequirement(name = "bearerAuth")
public class SpikeProtectedController {

    /**
     * MICRO-02 endpoint. Reached when the caller is authenticated at the
     * HTTP level (any method, but currently exercised only with a valid
     * Bearer JWT). No method-level authorization is applied here, so an
     * authenticated caller always receives {@code {"status":"AUTHENTICATED"}}.
     */
    @GetMapping(value = "/protected", produces = MediaType.APPLICATION_JSON_VALUE)
    public SpikeStatusResponse protectedEndpoint() {
        return new SpikeStatusResponse("AUTHENTICATED");
    }

    /**
     * MICRO-06A endpoint. The {@code @PreAuthorize("denyAll()")} expression
     * is a placeholder whose only job is to guarantee that any caller
     * reaching this method is rejected at the method level, regardless of
     * how they authenticated. This proves that Spring Method Security is
     * wired in and returns 403 rather than 401 (which would mean the caller
     * never got past authentication).
     *
     * A future MICRO will replace {@code "denyAll()"} with a real Space /
     * membership / role expression, but the endpoint path and the 403
     * contract will stay the same.
     *
     * The response body is deliberately the literal string
     * {@code "SHOULD_NOT_REACH"}: if a caller can read that body, the
     * method security layer has been bypassed and the SPIKE is broken.
     */
    @GetMapping(value = "/method-denied", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("denyAll()")
    public SpikeStatusResponse methodDeniedEndpoint() {
        return new SpikeStatusResponse("SHOULD_NOT_REACH");
    }

    /**
     * MICRO-06B-A endpoint. Demonstrates server-side Space Authorization
     * against a request-supplied {@code spaceId}:
     *
     * <ul>
     *   <li>{@code GET /api/v1/spike/spaces/space-A} with a valid Bearer
     *       JWT for {@code spike-user-1} → HTTP 200 +
     *       {@code {"spaceId":"space-A","status":"AUTHORIZED"}}.</li>
     *   <li>{@code GET /api/v1/spike/spaces/space-B} with the SAME valid
     *       Bearer JWT for the SAME {@code spike-user-1} → HTTP 403.</li>
     * </ul>
     *
     * The authorization decision is made entirely server-side by the
     * {@link SpikeSpaceAccess} bean via the {@code @PreAuthorize} SpEL
     * expression below. This controller does NOT inspect {@code spaceId}
     * itself and does NOT consult any role / permission / membership data.
     * That is deliberate: the whole point of this endpoint is to prove
     * that the space is supplied by the request, authenticated identity
     * is resolved by the Resource Server, and the authorization gate lives
     * in a dedicated server-side decision function.
     *
     * Once the production LearningSpace persistence layer lands, this
     * endpoint path and the {@code @PreAuthorize} SpEL expression will
     * stay identical; only the bean referenced by {@code @spikeSpaceAccess}
     * will be swapped for a real membership lookup.
     *
     * SPIKE ONLY. NOT a production API.
     */
    @GetMapping(value = "/spaces/{spaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@spikeSpaceAccess.canAccess(authentication, #spaceId)")
    public SpikeSpaceAuthorizationResponse spaceEndpoint(@PathVariable String spaceId) {
        return new SpikeSpaceAuthorizationResponse(spaceId, "AUTHORIZED");
    }
}
