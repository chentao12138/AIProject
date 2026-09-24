package com.aistudy.server.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "newPassword is required")
        @Size(min = 8, max = 255, message = "newPassword must be between 8 and 255 characters")
        String newPassword
) {
}
