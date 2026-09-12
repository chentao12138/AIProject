package com.aistudy.server.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * BUSINESS-019 — update user roles request.
 */
public record UpdateUserRolesRequest(
        @NotNull(message = "roles is required")
        @Size(min = 1, message = "roles must not be empty")
        List<String> roles
) {
}
