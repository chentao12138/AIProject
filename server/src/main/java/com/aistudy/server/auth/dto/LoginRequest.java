package com.aistudy.server.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * BUSINESS-017 — login request payload.
 */
public record LoginRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password
) {
}
