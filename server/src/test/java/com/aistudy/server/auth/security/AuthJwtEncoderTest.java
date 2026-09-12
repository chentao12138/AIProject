package com.aistudy.server.auth.security;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS-017 — HS256 JWT encoder secret handling unit test.
 */
class AuthJwtEncoderTest {

    private static final String TEST_SECRET =
            "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwQUJDREVGR0hJSktMTU5PUFJTVFVWV1hZWissLy8=";

    private static SecretKeySpec testSecretKey() {
        byte[] keyBytes = Base64.getDecoder().decode(TEST_SECRET.trim());
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    private static JwtEncoder testEncoder() {
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(testSecretKey()));
    }

    private static JwtDecoder testDecoder() {
        return NimbusJwtDecoder.withSecretKey(testSecretKey()).build();
    }

    @Test
    void encodedTokenContainsExpectedClaims() {
        JwtEncoder encoder = testEncoder();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("aistudy")
                .subject("test-subject")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .claim("tokenType", "access")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertNotNull(token);
        assertFalse(token.isBlank());
        String[] segments = token.split("\\.", -1);
        assertEquals(3, segments.length, "JWT must have 3 segments");

        Jwt decoded = testDecoder().decode(token);
        assertEquals("test-subject", decoded.getSubject());
        // getIssuer() would try to coerce the plain "aistudy" string to a URL;
        // the issuer claim is a plain string here, so assert it as one.
        assertEquals("aistudy", decoded.getClaimAsString("iss"));
        assertEquals("access", decoded.getClaimAsString("tokenType"));
    }

    @Test
    void blankSecretFailsFast() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret(" ");
        properties.getJwt().setAccessTokenTtlSeconds(900);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new AuthJwtEncoder(properties));
        assertTrue(exception.getMessage().contains("AUTH_JWT_SECRET"));
    }

    @Test
    void tooShortSecretFailsFast() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("YQ==");
        properties.getJwt().setAccessTokenTtlSeconds(900);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new AuthJwtEncoder(properties));
        assertTrue(exception.getMessage().contains("256 bits"));
    }
}
