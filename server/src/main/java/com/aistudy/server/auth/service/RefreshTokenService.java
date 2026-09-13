package com.aistudy.server.auth.service;

import com.aistudy.server.auth.entity.RefreshSession;
import com.aistudy.server.auth.mapper.RefreshSessionMapper;
import com.aistudy.server.auth.security.AuthProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * BUSINESS-018 / BUSINESS-019 — refresh token lifecycle utilities.
 *
 * <p>Extended in BUSINESS-019 to allow administrative revocation of
 * all active refresh sessions for a user.
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final java.util.Base64.Encoder B64 = java.util.Base64.getUrlEncoder().withoutPadding();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final String HEX_PREFIX = "hex:";

    private final long ttlSeconds;
    private final RefreshSessionMapper refreshSessionMapper;

    public RefreshTokenService(AuthProperties authProperties,
                               RefreshSessionMapper refreshSessionMapper) {
        this.ttlSeconds = Math.max(60, authProperties.getJwt().getRefreshTokenTtlSeconds());
        this.refreshSessionMapper = refreshSessionMapper;
    }

    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    public String hash(String token) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HEX_PREFIX + bytesToHex(hashed);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for refresh token hashing", exception);
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public LocalDateTime toDatabaseDateTime(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }

    /**
     * Revoke all active refresh sessions for the given user account.
     *
     * <p>This is used for administrative actions such as disabling an
     * account or mutating roles, so the caller should ensure it happens
     * in the same business transaction where the account/role state is
     * updated.
     */
    public void revokeAllForUser(long userAccountId, String reason) {
        LocalDateTime now = toDatabaseDateTime(java.time.Instant.now());
        RefreshSession revoke = new RefreshSession();
        revoke.setRevokedAt(now);
        revoke.setRevokeReason(reason);
        revoke.setUpdatedAt(now);
        refreshSessionMapper.revokeAllActiveForUser(userAccountId, now, reason);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
