package com.aistudy.server.source.controller;

import jakarta.validation.constraints.NotNull;

public record ReviewSourceRequest(
        @NotNull(message = "reviewStatus is required")
        String reviewStatus,

        String rejectedReason
) {
}
