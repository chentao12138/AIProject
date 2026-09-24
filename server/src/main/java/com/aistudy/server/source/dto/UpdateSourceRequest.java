package com.aistudy.server.source.dto;

import jakarta.validation.constraints.Size;

/**
 * BUSINESS-002 — update request for Source metadata.
 *
 * @param title        optional new title
 * @param description  optional new description
 */
public record UpdateSourceRequest(
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Size(max = 512, message = "description must be at most 512 characters")
        String description
) {
}
