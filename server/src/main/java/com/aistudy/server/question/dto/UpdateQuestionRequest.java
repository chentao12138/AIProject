package com.aistudy.server.question.dto;

import jakarta.validation.constraints.Size;

/**
 * BUSINESS-008 — update request for Question authoring.
 *
 * @param stem        optional new stem
 * @param explanation optional new explanation
 * @param difficulty  optional new difficulty
 */
public record UpdateQuestionRequest(
        @Size(max = 5000, message = "stem must be at most 5000 characters")
        String stem,

        @Size(max = 2000, message = "explanation must be at most 2000 characters")
        String explanation,

        @Size(max = 32, message = "difficulty must be at most 32 characters")
        String difficulty
) {
}
