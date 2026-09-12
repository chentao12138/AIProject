package com.aistudy.server.auth.dto;

import java.util.List;

/**
 * BUSINESS-019 — typed paged response for admin user list.
 */
public record AdminUserPageResponse(List<AdminUserSummary> items,
                                    int page,
                                    int size,
                                    long totalElements,
                                    int totalPages) {
}
