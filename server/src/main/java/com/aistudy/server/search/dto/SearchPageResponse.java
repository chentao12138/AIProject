package com.aistudy.server.search.dto;

import java.util.List;

public record SearchPageResponse(
        List<SearchResult> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
