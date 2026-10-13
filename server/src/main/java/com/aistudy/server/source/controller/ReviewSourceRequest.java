package com.aistudy.server.source.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/spaces/{spaceId}/sources/{sourceId}/review}.
 *
 * <h3>Why the whitelist lives here as well as in the service</h3>
 *
 * <p>{@code @NotNull} alone accepted any string, so {@code "APPROVED"} or a
 * skip-level {@code PUBLISHED} on a freshly registered source both persisted
 * (F-05). Bean validation rejects a value outside the review vocabulary with
 * 400 {@code VALIDATION_ERROR} before a request reaches persistence, and
 * {@code SourceService.review} repeats the check so a caller that skips HTTP
 * validation — an internal call, a future batch endpoint — still cannot write
 * an out-of-vocabulary status. The two layers answer different questions: this
 * one is "is that a review outcome at all" (400), the service adds "is it
 * reachable from this source's current status" (409).
 */
public record ReviewSourceRequest(
        /**
         * The review outcome; exactly one of
         * {@code SourceService.REVIEW_OUTCOMES} (NEEDS_REVIEW, PUBLISHED,
         * REJECTED). Case-sensitive by design: statuses are wire values.
         */
        @NotNull(message = "reviewStatus is required")
        @Pattern(
                regexp = "NEEDS_REVIEW|PUBLISHED|REJECTED",
                message = "reviewStatus must be one of the documented review outcomes: "
                        + "NEEDS_REVIEW, PUBLISHED, REJECTED")
        String reviewStatus,

        /**
         * Required for {@code REJECTED} (enforced in the service, because the
         * rule depends on {@code reviewStatus}); ignored for other outcomes.
         * Capped well below the V032 {@code rejected_reason TEXT} capacity.
         */
        @Size(max = 2000, message = "rejectedReason must be at most 2000 characters")
        String rejectedReason
) {
}
