package com.aistudy.server.auth.dto;

/**
 * BUSINESS-018 — successful authentication response payload.
 *
 * <p>Contains both a short-lived access token and a one-time-use
 * refresh token. The refresh token is opaque and must be stored
 * by the client exactly as issued.
 */
public record TokenPairResponse(String accessToken,
                                String tokenType,
                                long expiresIn,
                                String refreshToken,
                                long refreshExpiresIn) {
}
