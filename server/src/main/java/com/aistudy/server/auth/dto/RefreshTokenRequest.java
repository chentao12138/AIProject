package com.aistudy.server.auth.dto;

/**
 * BUSINESS-018 — refresh request payload.
 */
public record RefreshTokenRequest(String refreshToken) {
}
