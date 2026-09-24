package com.aistudy.server.knowledge.category.controller;

import jakarta.validation.constraints.Min;

public record ReorderCategoryRequest(
        @Min(value = 0, message = "sortOrder must be >= 0")
        Integer sortOrder
) {
}
