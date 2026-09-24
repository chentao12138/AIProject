package com.aistudy.server.exam.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * BUSINESS-012 — update request for Exam definition.
 *
 * @param title              optional new title
 * @param description        optional new description
 * @param timeLimitMinutes   optional new time limit
 */
public record UpdateExamRequest(
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Size(max = 1000, message = "description must be at most 1000 characters")
        String description,

        @Min(value = 1, message = "timeLimitMinutes must be >= 1")
        @Max(value = 600, message = "timeLimitMinutes must be at most 600")
        Integer timeLimitMinutes
) {
}
