package com.aistudy.server.spike.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
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

import org.springframework.core.annotation.Order;

/** SPIKE-004 MICRO-02 + MICRO-03A + MICRO-04B-A(FIX) + MICRO-05B-A — API
 * auth boundary, BCrypt PasswordEncoder, minimal JWT Access Token issuance,
 * and Bearer Token authentication via Resource Server.
 */
@EnableMethodSecurity
@Configuration
public class SpikeSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain spikeSecurityFilterChain(HttpSecurity http,
                                                       @Qualifier("jwtDecoder") JwtDecoder spikeJwtDecoder) throws Exception {
        http.securityMatcher("/health", "/v3/api-docs", "/v3/api-docs/**", "/api/v1/spike/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                // BUSINESS-001-FIX-01 / BUSINESS-002: the LearningSpace and
                // Source APIs are Bearer-only (no cookies), so CSRF must not
                // short-circuit these paths before authentication.
                // Path-scoped, NOT global — future cookie-based endpoints
                // handle CSRF/Origin/SameSite per their own paths (ADR-026).
                // Note: "/api/v1/spaces/**" already covers the nested Source
                // paths; the Source patterns below are declared explicitly so
                // the protected Bearer API surface is self-documenting.
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/v1/spaces",
                        "/api/v1/spaces/**",
                        "/api/v1/spaces/*/sources",
                        "/api/v1/spaces/*/sources/**"))
                // ELECTRON-CORS-001-B: explicit Browser CORS for the
                // Desktop renderer origins (http://localhost:5173 dev,
                // app://aistudy prod). The CorsConfigurationSource bean
                // lives in ServerCorsConfig; preflight OPTIONS is
                // answered by the CORS layer BEFORE authentication,
                // while real GET/POST still require Bearer JWT below.
                // No permitAll("/api/**"), no csrf.disable().
                .cors(Customizer.withDefaults())
                // Explicit SPIKE decoder binding: two JwtDecoder beans now
                // exist (spike "jwtDecoder" + production "authJwtDecoder"),
                // so oauth2ResourceServer must never type-resolve JwtDecoder.
                // @Qualifier("jwtDecoder") keeps the SPIKE trust domain
                // strictly separate from the production auth chain.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(spikeJwtDecoder)))
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
     * Scope: this decoder exists ONLY for the SPIKE trust domain. It is
     * explicitly wired into the SPIKE Bearer chain
     * ({@code spikeSecurityFilterChain} via {@code @Qualifier("jwtDecoder")}),
     * keeping SPIKE and production ({@code authJwtDecoder}) secrets separate.
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
