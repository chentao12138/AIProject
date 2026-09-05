package com.aistudy.server.spike.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;

/**
 * SPIKE-004 MICRO-02 + MICRO-03A + MICRO-04B-A(FIX) + MICRO-05B-A — API
 * auth boundary, BCrypt PasswordEncoder, minimal JWT Access Token issuance,
 * and Bearer Token authentication via Resource Server.
 *
 * Purpose (MICRO-02): prove that Spring Security can enforce a per-endpoint
 * auth boundary on this project:
 *   - GET /health              → anonymous 200 (public)
 *   - any other request        → anonymous 401 (protected)
 *
 * MICRO-03A added a BCrypt-based {@link PasswordEncoder} bean so that later
 * MICROs (real login API, UserDetailsService, refresh token) can encode and
 * verify passwords without re-deciding the algorithm here.
 *
 * MICRO-04B-A(FIX) provides minimal JWT Access Token <em>issuance</em>
 * capability. No Bearer authentication, no login endpoint, no refresh token,
 * no User or Role, no database. Just the ability to mint a JWT from a subject
 * string with HS256, and a service class that later MICROs can call.
 *
 * MICRO-05B-A wires the existing {@link JwtDecoder} bean into the Spring
 * Security Resource Server so that a request carrying
 * {@code Authorization: Bearer <JWT>} authenticates as an
 * {@code authenticated()} caller against the same {@code anyRequest()}
 * rule. No User / Role / UserDetailsService / AuthenticationProvider /
 * login API / session policy / CSRF change is introduced — the default
 * Resource Server configuration is used, which relies only on the
 * {@link JwtDecoder} bean already configured by this class.
 *
 * Scope:
 *   - /health → permitAll()
 *   - any other request → authenticated()
 *   - Unauthenticated access to a protected request returns HTTP 401
 *     (explicit {@link AuthenticationEntryPoint}).
 *   - Valid Bearer JWT accepted by the Resource Server authenticates the
 *     request as authenticated.
 *   - PasswordEncoder bean uses BCryptPasswordEncoder with library defaults
 *     (BCrypt does the salting and work-factor configuration itself; we do
 *     NOT pass a custom salt or strength).
 *   - A {@link SecretKey} bean is generated at Spring context startup by the
 *     JDK's {@link KeyGenerator} using the {@code HmacSHA256} algorithm with
 *     {@link KeyGenerator#init(int)} explicitly set to 256 bits. The key is
 *     only valid for the current application context lifetime — no
 *     persistence, no config file, no environment variable, no log output.
 *     SPIKE ONLY, NOT production key management.
 *   - A {@link JwtEncoder} bean wraps the SecretKey via Nimbus
 *     {@link ImmutableSecret<SecurityContext>} so that the encoder can
 *     retrieve the key lazily during signing. HS256 is selected per-encode at
 *     the {@code JwsHeader} level (see {@link SpikeJwtTokenService}), not at
 *     the encoder bean level.
 *   - A {@link JwtDecoder} bean shares the SAME SecretKey so that the
 *     Resource Server can verify HS256 tokens issued by the encoder.
 *   - No custom Bearer filter, no Login, no UserDetailsService, no
 *     session policy change, no CSRF disable, no Role / spaceId / permission
 *     claim mapping.
 *
 * Why HS256 and no production key strategy?
 *   HS256 uses a single shared secret and is appropriate for a SPIKE that
 *   only demonstrates the encode pipeline. Production-grade JWT typically
 *   uses RS256/ES256 with a public/private key pair or a JWKS endpoint;
 *   that decision is deferred to a future ADR. Hard-coding a secret string
 *   here would bake a security-incorrect assumption into the codebase from
 *   the very first JWT MICRO, which we want to avoid.
 *
 * Why an explicit AuthenticationEntryPoint?
 *   Without it, Spring Security might produce a 403 for unauthenticated access
 *   (which is reserved for authenticated-but-not-authorized). We want a stable
 *   contract for the SPIKE: anonymous callers always get 401. This is kept
 *   during MICRO-05B-A: with the Resource Server now in the pipeline, the
 *   anonymous case still takes the same path and returns 401. Invalid or
 *   expired Bearer tokens are not covered in this MICRO — that is deferred to
 * a future MICRO which will decide whether invalid Bearer stays 401 or
 * moves to another handler.
 *
 * MICRO-06A enables Spring Method Security via {@code @EnableMethodSecurity}
 * on this class so that a request that has already authenticated (e.g. via
 * a valid Bearer JWT) can still be subject to method-level authorization.
 * A future MICRO will swap the placeholder {@code @PreAuthorize("denyAll()")}
 * on {@link SpikeProtectedController#methodDeniedEndpoint()} for real
 * Space / membership / role authorization rules.
 */
@EnableMethodSecurity
@Configuration
public class SpikeSecurityConfig {

    @Bean
    public SecurityFilterChain spikeSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new Anonymous401EntryPoint()));
        return http.build();
    }

    /**
     * BCrypt-based password encoder used by later MICROs. The bean exposes the
     * {@link PasswordEncoder} interface so callers depend on the abstraction;
     * the concrete BCrypt algorithm can be swapped by a future ADR without
     * touching consuming code.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * SPIKE ONLY. NOT production key management.
     *
     * A runtime-random HMAC-SHA256 SecretKey, generated at Spring context
     * startup by the JDK's {@link KeyGenerator}. The key is only valid for
     * the current application context: on restart a new random key is
     * produced, which means any JWT issued by this instance would no longer
     * verify after restart. That is intentionally acceptable for a SPIKE
     * that only proves the encoding pipeline exists and produces valid JWT
     * compact serialization.
     *
     * {@link KeyGenerator#init(int)} is called explicitly with 256 bits to
     * guarantee the HS256 key length regardless of what the JDK platform's
     * default {@code HmacSHA256} key length happens to be on this runtime.
     *
     * Explicit constraints:
     *   - No hard-coded secret string.
     *   - No reading from application.yml, environment, or property source.
     *   - No writing to log, file, or network.
     *   - No Base64 output or key material exposure outside this bean.
     *
     * A real deployment MUST replace this with a proper key management
     * strategy (env-loaded KMS-backed key, JWK URL, or HSM reference).
     * This SPIKE deliberately avoids that decision.
     *
     * @throws NoSuchAlgorithmException if the runtime does not offer the
     *         {@code HmacSHA256} key generator. All supported JDK 21
     *         distributions ship this algorithm, so in practice this bean
     *         method should never throw on this project's runtime.
     */
    @Bean
    public SecretKey spikeJwtSecretKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("HmacSHA256");
        keyGenerator.init(256);
        return keyGenerator.generateKey();
    }

    /**
     * Nimbus JwtEncoder for signing HS256 JWTs. The SecretKey from
     * {@link #spikeJwtSecretKey()} is wrapped as a Nimbus
     * {@link ImmutableSecret<SecurityContext>}, which is the minimal
     * {@code JWKSource<SecurityContext>} implementation that Nimbus provides
     * for shared-secret / HMAC algorithms. The encoder accepts the
     * {@link SecurityContext} (always {@code null} in this SPIKE — no
     * per-request secret lookup) and returns the same key every call.
     *
     * The HS256 algorithm is NOT chosen here; it is chosen per-encode in
     * {@link SpikeJwtTokenService} via a {@code JwsHeader}, so that later
     * MICROs (Bearer auth) can vary algorithm per request if needed.
     *
     * This is Spring Security 6.5's documented pattern for HMAC-based JWT
     * encoding.
     */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey spikeJwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(spikeJwtSecretKey));
    }

    /**
     * Nimbus JwtDecoder for verifying HS256-signed JWTs using the SAME
     * SecretKey as {@link #jwtEncoder(SecretKey)}. The SPIKE has exactly one
     * signing key; both encoder and decoder resolve to it through the shared
     * {@link SecretKey} bean. No second key is ever generated.
     *
     * Algorithm selection is done here at the decoder level with
     * {@link MacAlgorithm#HS256} so that any attempt to decode a JWT with a
     * different algorithm fails fast with a {@link
     * org.springframework.security.oauth2.jwt.JwtException} rather than
     * silently accepting a downgrade.
     *
     * Signature verification is performed by Nimbus JOSE internally: a
     * tampered header, payload, or signature segment will throw
     * {@link org.springframework.security.oauth2.jwt.JwtException}. The
     * test in {@code SpikeJwtTokenServiceTest} exercises that failure path
     * by mutating the payload segment.
     *
     * Scope: this decoder exists ONLY for the SPIKE to prove that the issued
     * JWT can round-trip through the same SecretKey. It is NOT wired into
     * Bearer authentication; that is a future MICRO.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey spikeJwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(spikeJwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Returns HTTP 401 for any unauthenticated request hitting a protected
     * resource. No redirect, no form login, no 403. Deterministic contract for
     * the boundary test and for downstream callers once real auth is added.
     */
    private static final class Anonymous401EntryPoint implements AuthenticationEntryPoint {

        @Override
        public void commence(HttpServletRequest request,
                             HttpServletResponse response,
                             AuthenticationException authException) throws java.io.IOException {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
