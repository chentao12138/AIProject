package com.aistudy.server.spike.openapi.model;

/**
 * SPIKE-005 MICRO-03A — typed response for the two SPIKE endpoints that
 * both return a single {@code status} field:
 *
 * <ul>
 *   <li>{@code GET /api/v1/spike/protected} — the MICRO-02 gate. Returns
 *       {@code status = "AUTHENTICATED"} for a valid Bearer JWT.</li>
 *   <li>{@code GET /api/v1/spike/method-denied} — the MICRO-06A gate.
 *       The {@code @PreAuthorize("denyAll()")} expression guarantees
 *       every real caller is rejected with 403 before the method runs,
 *       so in practice this type is only produced when the endpoint is
 *       invoked in a context that bypasses {@code denyAll()} (which the
 *       SPIKE deliberately does not). Its presence in the method
 *       signature is what lets springdoc see a typed return and emit a
 *       structured schema rather than a generic Map schema.</li>
 * </ul>
 *
 * <p>Reused across both endpoints because they share the same single
 * string field and the same status-literal shape. Introducing two
 * separate record types for the same shape would be strictly more code
 * with no additional information.
 *
 * <h3>SPIKE-only</h3>
 *
 * <p>Like {@link SpikeHealthResponse}, this type lives under
 * {@code com.aistudy.server.spike.openapi.model} and is not a
 * production DTO. Do not move it into a non-spike package or reuse it
 * in business modules.
 *
 * <h3>Field semantics</h3>
 *
 * <p>{@code status}: a single string literal indicating the outcome.
 * Current allowed values:
 *
 * <ul>
 *   <li>{@code "AUTHENTICATED"} — for {@code /api/v1/spike/protected}.</li>
 *   <li>{@code "SHOULD_NOT_REACH"} — for
 *       {@code /api/v1/spike/method-denied} (defensive literal that
 *       would be observed only if the SPIKE were broken).</li>
 * </ul>
 *
 * <p>No validation is applied; the value is a plain string field. If
 * a later SPIKE wants to enforce a closed enum, that is a separate
 * decision and not this MICRO's concern.
 */
public record SpikeStatusResponse(
        String status
) {
}
