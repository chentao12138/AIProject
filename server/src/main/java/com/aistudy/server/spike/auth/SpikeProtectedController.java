package com.aistudy.server.spike.auth;

import java.util.Map;

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
 */
@RestController
@RequestMapping("/api/v1/spike")
public class SpikeProtectedController {

    /**
     * MICRO-02 endpoint. Reached when the caller is authenticated at the
     * HTTP level (any method, but currently exercised only with a valid
     * Bearer JWT). No method-level authorization is applied here, so an
     * authenticated caller always receives {@code {"status":"AUTHENTICATED"}}.
     */
    @GetMapping("/protected")
    public Map<String, String> protectedEndpoint() {
        return Map.of("status", "AUTHENTICATED");
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
    @GetMapping("/method-denied")
    @PreAuthorize("denyAll()")
    public Map<String, String> methodDeniedEndpoint() {
        return Map.of("status", "SHOULD_NOT_REACH");
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
    @GetMapping("/spaces/{spaceId}")
    @PreAuthorize("@spikeSpaceAccess.canAccess(authentication, #spaceId)")
    public Map<String, String> spaceEndpoint(@PathVariable String spaceId) {
        return Map.of("spaceId", spaceId, "status", "AUTHORIZED");
    }
}
