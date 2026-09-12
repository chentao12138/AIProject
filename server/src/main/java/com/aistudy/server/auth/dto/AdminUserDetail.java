package com.aistudy.server.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-019 — typed admin user detail.
 */
public record AdminUserDetail(Long id,
                              String subject,
                              String username,
                              String status,
                              List<String> roles,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
}
