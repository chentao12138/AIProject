package com.aistudy.server.space.dto;

import jakarta.validation.constraints.Size;

public record RenameLearningSpaceRequest(
        @Size(max = 128, message = "name must be at most 128 characters")
        String name,

        @Size(max = 512, message = "description must be at most 512 characters")
        String description
) {
}
