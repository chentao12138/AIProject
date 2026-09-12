package com.aistudy.server.auth.dto;

import java.util.List;

/**
 * BUSINESS-019 — typed admin user summary.
 */
public record AdminUserSummary(Long id,
                               String subject,
                               String username,
                               String status,
                               List<String> roles,
                               java.time.LocalDateTime createdAt) {
}
