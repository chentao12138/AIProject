package com.aistudy.server.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * BUSINESS-017 — auth configuration properties.
 *
 * <p>The secret itself is intentionally not stored in any generated
 * docs or application YAML beyond the ${AUTH_JWT_SECRET} placeholder.
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private final Jwt jwt = new Jwt();

    public Jwt getJwt() {
        return jwt;
    }

    public static class Jwt {

        /**
         * Base64-encoded HMAC secret. Must provide at least 256 bits
         * of entropy for HS256.
         */
        private String secret = "";

        private long accessTokenTtlSeconds = 900;

        private long refreshTokenTtlSeconds = 2592000;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getAccessTokenTtlSeconds() {
            return accessTokenTtlSeconds;
        }

        public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
            this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        }

        public long getRefreshTokenTtlSeconds() {
            return refreshTokenTtlSeconds;
        }

        public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
            this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        }
    }
}
