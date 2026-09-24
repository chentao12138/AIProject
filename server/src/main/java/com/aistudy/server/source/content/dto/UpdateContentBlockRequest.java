package com.aistudy.server.source.content.dto;

import jakarta.validation.constraints.Size;

public record UpdateContentBlockRequest(
        @Size(max = 100000, message = "normalizedText must be at most 100000 characters")
        String normalizedText,

        String structuredDataJson,

        String locatorJson
) {
}
