package com.aistudy.server.auth.dto;

/**
 * BUSINESS-018 — logout request payload.
 */
public record LogoutRequest(String refreshToken) {
}
