package com.aistudy.server.auth.dto;

import java.util.List;

/**
 * BUSINESS-017 / BUSINESS-019 — current authenticated user response.
 */
public record CurrentUserResponse(String subject, String username, List<String> roles) {
}
