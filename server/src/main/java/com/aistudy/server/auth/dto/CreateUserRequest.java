package com.aistudy.server.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "username is required")
        @Size(max = 128, message = "username must be at most 128 characters")
        String username,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 255, message = "password must be between 8 and 255 characters")
        String password,

        @Size(max = 64, message = "subject must be at most 64 characters")
        String subject
) {
}
