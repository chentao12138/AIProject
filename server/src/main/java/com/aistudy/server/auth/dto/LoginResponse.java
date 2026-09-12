package com.aistudy.server.auth.dto;

/**
 * BUSINESS-017 — login response payload.
 *
 * <p>{@code accessToken} is {@code null} when authentication fails,
 * allowing the controller to return HTTP 401 with the same shape.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
}
