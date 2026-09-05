package com.aistudy.server.spike.auth;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * SPIKE-004 MICRO-04B-A(FIX) + MICRO-04B-B(FIX) — minimal JWT Access Token issuance.
 *
 * This class exists ONLY so that a later MICRO can call
 * {@link #issueAccessToken(String)} to obtain a signed JWT without re-deciding
 * claims or signing strategy. Nothing here authenticates callers, nothing here
 * reads a request header, nothing here touches a database.
 *
 * Fixed claims (by SPIKE design):
 *   - iss = {@code "aistudy-spike"}
 *   - sub = the subject passed to {@link #issueAccessToken(String)}
 *   - iat = current time (millisecond precision)
 *   - exp = iat + 5 minutes
 *
 * Explicitly NOT included yet:
 *   - role / roles / spaceId / permissions / username / email
 *   - refreshToken / jti
 *
 * The algorithm is HS256, chosen because it is symmetric and needs no
 * key pair. HS256 is selected here at the {@link JwsHeader} level via
 * {@link MacAlgorithm#HS256}; the {@link JwtEncoder} bean configured in
 * {@link SpikeSecurityConfig} is algorithm-agnostic at construction time and
 * will use whatever algorithm the header specifies. This class does not
 * construct JWS by hand.
 *
 * No {@code kid} (key ID) header is set. The SPIKE has exactly one runtime
 * SecretKey with no rotation, key versioning, or JWK Set — a stable kid
 * would be misleading. Nimbus selects the signing key from
 * {@link com.nimbusds.jose.jwk.source.ImmutableSecret<com.nimbusds.jose.proc.SecurityContext>}
 * which has no kid metadata; forcing a kid would cause
 * "Failed to select a JWK signing key". Key rotation / key management
 * is deferred to a future ADR.
 *
 * Lifetime of 5 minutes is a placeholder chosen so that later MICROs can
 * assert exp is approximately 300 seconds after iat without waiting.
 */
@Component
public class SpikeJwtTokenService {

    /** Fixed issuer string. Do not rename without a future ADR. */
    public static final String SPIKE_ISSUER = "aistudy-spike";

    /** Token lifetime: 5 minutes. Placeholder; production will decide this. */
    public static final long TOKEN_LIFETIME_MINUTES = 5;

    private final JwtEncoder jwtEncoder;

    public SpikeJwtTokenService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    /**
     * Issues a JWT access token for the given subject.
     *
     * @param subject the value to place in the {@code sub} claim; typically a
     *                user id or stable identifier. Not enforced here.
     * @return the compact-serialized JWT string (three dot-separated segments:
     *         header.payload.signature). Never null.
     * @throws org.springframework.security.oauth2.jwt.JwtEncodingException if
     *         the encoder rejects the claims or header (should not happen with
     *         the current SPIKE configuration).
     */
    public String issueAccessToken(String subject) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant exp = now.plus(TOKEN_LIFETIME_MINUTES, ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SPIKE_ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(exp)
                .build();

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256)
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims))
                .getTokenValue();
    }
}
