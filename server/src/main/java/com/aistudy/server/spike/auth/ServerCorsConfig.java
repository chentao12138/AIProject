package com.aistudy.server.spike.auth;

import com.aistudy.server.config.properties.CorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * ELECTRON-CORS-001-B — explicit Browser CORS policy for the AIStudy
 * Desktop client.
 *
 * <p>The Desktop renderer runs on two explicit origins (verified by the
 * frontend spike ELECTRON-CORS-001-A with a real Electron 44 runtime
 * probe — the production Origin header is exactly {@code app://aistudy},
 * never {@code Origin:null}):
 * <ul>
 *   <li>{@code http://localhost:5173} — development renderer (Vite).</li>
 *   <li>{@code app://aistudy} — production renderer (custom standard +
 *       secure Electron scheme).</li>
 * </ul>
 *
 * <p>Contract:
 * <ul>
 *   <li>Standard Spring Security integration: a
 *       {@link CorsConfigurationSource} bean + {@code http.cors(...)} —
 *       NO {@code @CrossOrigin} scattered on controllers, NO custom
 *       Servlet filter hand-rolling ACAO headers, NO {@code *}.</li>
 *   <li>Policy is registered ONLY for {@code /api/**} business APIs.
 *       {@code /health} and {@code /v3/api-docs} keep their existing
 *       policies untouched.</li>
 *   <li>Methods: GET / POST / OPTIONS — the only methods that exist on
 *       the current real controllers (verified by scanning every
 *       {@code @RequestMapping} in the codebase; no PUT/PATCH/DELETE
 *       exists yet).</li>
 *   <li>Allowed request headers: Authorization / Content-Type / Accept.
 *       Spring matches {@code Access-Control-Request-Headers}
 *       case-insensitively, so browser preflights carrying
 *       {@code authorization,content-type} are satisfied.</li>
 *   <li>No exposed headers (the UI reads no custom response headers).</li>
 *   <li>{@code allowCredentials = false} — auth is
 *       {@code Authorization: Bearer}, never browser cookies. Cookie
 *       auth, if it ever arrives, must be redesigned separately.</li>
 *   <li>Preflight cache: 1 hour.</li>
 *   <li>Origins are externalized via {@code aistudy.cors.allowed-origins}
 *       (env override {@code AISTUDY_CORS_ALLOWED_ORIGINS}, comma
 *       separated) with a SECURE default — missing env never degrades
 *       to {@code *}.</li>
 * </ul>
 *
 * <p>Preflight requests are answered by the CORS layer BEFORE
 * authentication; real GET/POST requests still go through the existing
 * {@link SpikeSecurityConfig} chain (Bearer JWT required, CSRF
 * path-scoped rules unchanged, no {@code permitAll("/api/**")}).
 */
@Configuration
public class ServerCorsConfig {

    private final List<String> allowedOrigins;

    public ServerCorsConfig(CorsProperties corsProperties) {
        this.allowedOrigins = List.copyOf(corsProperties.getAllowedOrigins());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));
        // exposedHeaders: intentionally empty — the business UI does not
        // read custom response headers (no Authorization echo, no
        // Set-Cookie, no internal tracing headers).

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
