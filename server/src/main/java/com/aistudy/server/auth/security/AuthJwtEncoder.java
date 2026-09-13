package com.aistudy.server.auth.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * BUSINESS-017 — shared HS256 JWT encoder backed by a Base64 secret.
 *
 * <p>This component exists so production and any future auth consumers
 * do not each re-implement {@code Base64(secret) -> SecretKey -> HMAC}.
 * The SPIKE path keeps using {@code SpikeJwtTokenService}; only the
 * production auth package depends on this encoder.
 */
@Component
public class AuthJwtEncoder implements JwtEncoder {

    private final JwtEncoder delegate;

    public AuthJwtEncoder(AuthProperties properties) {
        this.delegate = new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey(properties)));
    }

    @Override
    public Jwt encode(JwtEncoderParameters parameters) {
        return delegate.encode(parameters);
    }

    private SecretKeySpec hmacKey(AuthProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET is required for production JWT signing");
        }
        byte[] keyBytes = Base64.getDecoder().decode(secret.trim());
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must decode to at least 256 bits for HS256");
        }
        return new SecretKeySpec(keyBytes, MacAlgorithm.HS256.name());
    }
}
