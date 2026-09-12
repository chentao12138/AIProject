package com.aistudy.server.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * BUSINESS-019 — update user status request.
 */
public record UpdateUserStatusRequest(
        @NotNull(message = "status is required")
        UserStatus status
) {
}
