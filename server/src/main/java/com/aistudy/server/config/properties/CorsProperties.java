package com.aistudy.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CORS policy for {@code aistudy.cors.*}.
 *
 * <p>Development origins must be supplied explicitly by the active profile.
 * An empty default prevents localhost origins from leaking into production.
 */
@ConfigurationProperties(prefix = "aistudy.cors")
public class CorsProperties {

    /**
     * Allowed origins. Spring Boot binds YAML lists directly; keep this
     * mutable so comma-separated env overrides still work.
     */
    private List<String> allowedOrigins = new ArrayList<>();

    public List<String> getAllowedOrigins() {
        return allowedOrigins == null ? Collections.emptyList() : Collections.unmodifiableList(allowedOrigins);
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }
}
