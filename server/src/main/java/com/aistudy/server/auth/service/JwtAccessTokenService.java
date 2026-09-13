package com.aistudy.server.auth.service;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.security.AuthJwtEncoder;
import com.aistudy.server.auth.security.AuthProperties;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

/**
 * BUSINESS-017 — short-lived access token service.
 *
 * <p>Uses the shared {@link AuthJwtEncoder} to issue tokens whose
 * subject matches the stable {@code user_account.subject}.
 */
@Service
public class JwtAccessTokenService {

    private final JwtEncoder authJwtEncoder;
    private final long ttlSeconds;

    public JwtAccessTokenService(AuthJwtEncoder authJwtEncoder, AuthProperties authProperties) {
        this.authJwtEncoder = authJwtEncoder;
        this.ttlSeconds = Math.max(1, authProperties.getJwt().getAccessTokenTtlSeconds());
    }

    public String issueAccessToken(String subject, java.util.Collection<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer("aistudy")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .claim("tokenType", "access");
        if (roles != null) {
            builder.claim("roles", new java.util.ArrayList<>(roles));
        }
        JwtClaimsSet claims = builder.build();
        return authJwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims)).getTokenValue();
    }

    public String issueAccessToken(String subject) {
        return issueAccessToken(subject, java.util.List.of("USER"));
    }
}
