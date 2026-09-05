package com.aistudy.server.spike.openapi.model;

/**
 * SPIKE-005 MICRO-03A — typed response for
 * {@code GET /api/v1/spike/spaces/{spaceId}}.
 *
 * <p>Purpose: replace the previous
 * {@code Map<String, String>} return type on
 * {@link com.aistudy.server.spike.auth.SpikeProtectedController#spaceEndpoint}
 * so springdoc emits a structured OpenAPI schema for the space
 * authorization SPIKE endpoint instead of a generic
 * {@code {type: object, additionalProperties: {type: string}}} schema.
 *
 * <h3>SPIKE-only</h3>
 *
 * <p>Like {@link SpikeHealthResponse} and {@link SpikeStatusResponse},
 * this type lives under
 * {@code com.aistudy.server.spike.openapi.model} and is not a
 * production DTO. Once the production LearningSpace API is
 * introduced, this record is deleted along with the SPIKE controller
 * that consumes it.
 *
 * <h3>Field semantics</h3>
 *
 * <ul>
 *   <li>{@code spaceId}: the request-supplied path variable. Echoed
 *       back unchanged from
 *       {@code @PathVariable String spaceId}. Not sourced from JWT
 *       claims; not sourced from a database lookup; purely a
 *       request-echo so an operator can correlate a 200 response with
 *       the URL that produced it.</li>
 *   <li>{@code status}: literal
 *       {@code "AUTHORIZED"} — always this value for a 200 response
 *       from the space endpoint. A 403 response is produced by the
 *       filter chain before the method runs, so it does not flow
 *       through this record.</li>
 * </ul>
 */
public record SpikeSpaceAuthorizationResponse(
        String spaceId,
        String status
) {
}
