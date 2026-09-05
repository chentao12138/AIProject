package com.aistudy.server.spike.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE-004 MICRO-04C-A + MICRO-04C-A-FIX — JWT round-trip smoke test.
 *
 * Extends MICRO-04B-A's shape-only assertions with:
 *   - HS256 signature verification via {@link JwtDecoder} using the same
 *     SecretKey as the encoder
 *   - Claim extraction (iss / sub / iat / exp)
 *   - 5-minute lifetime check
 *   - Header alg check (HS256)
 *   - Tampered payload must throw {@link JwtException}
 *
 * MICRO-04C-A-FIX tightened the tamper strategy: instead of flipping a
 * single Base64URL character (which could corrupt the JSON and mask the
 * signature check with a parse failure), the payload is now decoded to
 * JSON, its {@code sub} claim value replaced with an equal-length value,
 * re-encoded to Base64URL without padding, and reassembled with the
 * original header and signature. Any {@link JwtException} that follows can
 * therefore only be attributed to a signature mismatch, not malformed
 * payload JSON.
 *
 * Deliberately does NOT print token, claims, or secret material.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpikeJwtTokenServiceTest {

    @Autowired
    private SpikeJwtTokenService tokenService;

    @Autowired
    private JwtDecoder jwtDecoder;

    /**
     * MICRO-08A: mock the SPIKE-only membership repository so this
     * JWT-focused test does not need a real {@code JdbcTemplate} or
     * {@code DataSource} bean on the classpath.
     *
     * <h3>Why this mock is required</h3>
     *
     * The {@code test} profile (see {@code application-test.yml})
     * deliberately excludes {@code DataSourceAutoConfiguration} and
     * {@code JdbcTemplateAutoConfiguration} so unit-oriented tests
     * run without a database. However, since MICRO-07E-A wired
     * {@link SpikeSpaceAccess} to constructor-inject
     * {@link SpikeSpaceMembershipRepository}, and since
     * {@link SpikeSpaceMembershipRepository} itself constructor-
     * injects {@code JdbcTemplate}, any {@code @SpringBootTest} that
     * loads the full application context under the {@code test}
     * profile fails with:
     * <pre>
     *   No qualifying bean of type
     *     'org.springframework.jdbc.core.JdbcTemplate'
     * </pre>
     *
     * Replacing the repository bean with a Mockito mock via
     * {@code @MockitoBean} prevents Spring from ever trying to call
     * the real repository's constructor, which in turn means the
     * missing {@code JdbcTemplate} dependency is never resolved.
     *
     * <h3>Why not stub the mock in this test</h3>
     *
     * This class tests {@link SpikeJwtTokenService#issueAccessToken(String)}
     * and the Resource Server's {@link JwtDecoder} — it has no
     * business calling {@link SpikeSpaceAccess} or its repository.
     * Therefore we add no {@code when(...)}, {@code given(...)},
     * or {@code verify(...)} calls. The mock exists purely to keep
     * the Spring context bootable; any real interaction with the
     * repository from a JWT test would be a bug in this test.
     *
     * <h3>Why not change the profile</h3>
     *
     * This test was written before MICRO-07D-A added the repository,
     * so it was already correct under the {@code test} profile.
     * Switching it to a database-backed profile (e.g. {@code
     * flyway-it}) would force this JWT smoke test to stand up a
     * real MySQL, which is the wrong test for that. The
     * repository-to-database path is owned by
     * {@code SpikeSpaceMembershipIntegrationTest} and
     * {@code SpikeSpaceAuthorizationEndToEndIntegrationTest}.
     */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    @Test
    void issueAccessTokenRoundTripsThroughJwtDecoder() {
        String token = tokenService.issueAccessToken("spike-user-1");

        // --- Shape checks (MICRO-04B-A) ---
        // 1. non-null
        assertNotNull(token, "token must not be null");

        // 2. non-blank
        assertFalse(token.isBlank(), "token must not be blank");

        // 3. exactly 3 dot-separated segments (header.payload.signature)
        String[] segments = token.split("\\.", -1);
        assertEquals(3, segments.length,
                "JWT compact serialization must have exactly 3 dot-separated segments, but got: "
                        + token.length() + " chars");

        // 4. each segment non-empty (a blank segment would indicate a
        //    malformed JWT, e.g. "a.b.c." or ".a.b.c")
        for (int i = 0; i < segments.length; i++) {
            assertFalse(segments[i].isEmpty(),
                    "JWT segment #" + i + " must not be empty");
        }

        // --- Decode + signature verification (MICRO-04C-A) ---

        // 5. decoded is not null
        Jwt decoded = jwtDecoder.decode(token);
        assertNotNull(decoded, "decoded Jwt must not be null");

        // 6. sub claim round-trips
        assertEquals("spike-user-1", decoded.getSubject(),
                "sub claim must equal the subject passed to issueAccessToken");

        // 7. iss claim round-trips
        //     Use getClaimAsString("iss") rather than getIssuer() because the
        //     SPIKE issuer value is the plain string "aistudy-spike", which is
        //     not a valid URL. ClaimAccessor.getIssuer() would attempt a
        //     URL/URI conversion and throw IllegalArgumentException for a
        //     non-URL value. Reading the raw string claim avoids that
        //     conversion while still asserting the iss round-trip.
        String issuer = decoded.getClaimAsString("iss");
        assertNotNull(issuer, "iss claim must not be null");
        assertEquals("aistudy-spike", issuer,
                "iss claim must round-trip as 'aistudy-spike'");

        // 8. iat is present
        assertNotNull(decoded.getIssuedAt(), "iat claim must not be null");

        // 9. exp is present
        assertNotNull(decoded.getExpiresAt(), "exp claim must not be null");

        // 10. exp strictly later than iat
        assertTrue(decoded.getExpiresAt().isAfter(decoded.getIssuedAt()),
                "exp must be strictly later than iat");

        // 11. exp - iat is exactly 5 minutes (service truncates to MILLIS,
        //     so a Duration equality should be exact)
        Duration lifetime = Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt());
        assertEquals(Duration.ofMinutes(5), lifetime,
                "token lifetime must be exactly 5 minutes");

        // 12. alg header is HS256
        Object alg = decoded.getHeaders().get("alg");
        assertEquals("HS256", alg,
                "JWS header alg must be HS256, got: " + alg);

        // 13. tampered token must fail signature verification with JwtException
        String tamperedToken = tamperPayloadSegment(token);

        // Sanity: the tampered token has the same segment count but a
        // different payload segment, so it looks like a plausible JWT.
        String[] tamperedSegments = tamperedToken.split("\\.", -1);
        assertEquals(3, tamperedSegments.length);
        assertNotEquals(segments[1], tamperedSegments[1],
                "tampered token payload must differ from original");

        assertThrows(JwtException.class,
                () -> jwtDecoder.decode(tamperedToken),
                "decoding a token with a tampered payload must throw JwtException");
    }

    /**
     * Returns a token that has the SAME header and signature segments but a
     * modified payload segment whose JSON is still valid. The modification
     * replaces the {@code sub} claim value from {@code spike-user-1} to
     * {@code spike-user-2} (both 12 characters, so the JSON shape and
     * segment length remain plausible). Because the original signature was
     * computed over the ORIGINAL payload, decoding the tampered token MUST
     * fail with a signature verification error — not a JSON parsing error.
     *
     * This is important: flipping a single Base64URL character would
     * corrupt the JSON and could fail parsing before signature verification
     * runs, which would not strictly prove the signature check works. The
     * JSON-preserving tamper strategy guarantees that any
     * {@link JwtException} the decoder throws can only come from the
     * signature mismatch.
     *
     * @throws IllegalStateException if the subject replacement did not
     *         happen (defensive: should never occur with the current service
     *         implementation, but we assert explicitly rather than rely on
     *         the JSON string matching expectations).
     *
     * Does not print the token, payload, or secret material.
     */
    private static String tamperPayloadSegment(String token) {
        String[] segments = token.split("\\.", -1);

        // 1. Decode the original payload to UTF-8 JSON.
        String originalJson = new String(
                Base64.getUrlDecoder().decode(segments[1]),
                StandardCharsets.UTF_8);

        // 2. Replace the sub claim value inside the JSON.
        //    Both strings are 12 characters so the modified JSON is
        //    syntactically and semantically valid.
        String modifiedJson = originalJson.replace(
                "\"spike-user-1\"",
                "\"spike-user-2\"");

        // 3. Prove the replacement actually happened.
        if (modifiedJson.equals(originalJson)) {
            throw new IllegalStateException(
                    "subject replacement did not occur; tamper is invalid");
        }

        // 4. Re-encode the modified JSON using Base64 URL without padding.
        String modifiedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(modifiedJson.getBytes(StandardCharsets.UTF_8));

        // 5. Reassemble: original header + modified payload + original signature.
        //    The signature segment is deliberately left untouched so that any
        //    decoder failure can only be attributed to signature mismatch.
        return segments[0] + "." + modifiedPayload + "." + segments[2];
    }
}
