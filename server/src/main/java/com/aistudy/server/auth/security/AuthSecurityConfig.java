package com.aistudy.server.auth.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * BUSINESS-017 / BUSINESS-019 — production auth security boundary.
 *
 * <p>This chain only owns {@code /api/v1/**} and intentionally runs
 * after the SPIKE chain so existing {@code SpikeSecurityConfig}
 * tests keep their matcher priority.
 */
@Configuration
@EnableMethodSecurity
public class AuthSecurityConfig {

    @Bean(name = "authSecurityFilterChain")
    @Order(2)
    public SecurityFilterChain authSecurityFilterChain(HttpSecurity http,
                                                       @Qualifier("authJwtDecoder") JwtDecoder authJwtDecoder,
                                                       @Qualifier("authJwtAuthenticationConverter") JwtAuthenticationConverter authJwtAuthenticationConverter) throws Exception {
        http
            .securityMatcher("/api/v1/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                    (request, response, authException) -> response
                            .sendError(org.springframework.http.HttpStatus.UNAUTHORIZED.value())))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/login").permitAll()
                    .requestMatchers("/api/v1/auth/refresh").permitAll()
                    .requestMatchers("/api/v1/auth/logout").permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/auth/me").authenticated()
                    .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.decoder(authJwtDecoder)
                            .jwtAuthenticationConverter(authJwtAuthenticationConverter)))
            .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean(name = "authJwtDecoder")
    public JwtDecoder authJwtDecoder(AuthProperties properties) {
        return NimbusJwtDecoder.withSecretKey(hmacKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean(name = "authJwtAuthenticationConverter")
    public JwtAuthenticationConverter authJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRoleConverter());
        return converter;
    }

    private SecretKeySpec hmacKey(AuthProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET is required for production JWT verification");
        }
        byte[] keyBytes = Base64.getDecoder().decode(secret.trim());
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must decode to at least 256 bits for HS256");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}

