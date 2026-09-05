package com.aistudy.server.spike.openapi.model;

/**
 * SPIKE-005 MICRO-03A — typed response for {@code GET /health}.
 *
 * <p>Purpose: replace the previous {@code Map<String, String>} return type
 * on {@link com.aistudy.server.spike.SpikeHealthController} so that
 * springdoc can emit a structured OpenAPI schema (with explicit
 * {@code properties}) instead of the generic
 * {@code {type: object, additionalProperties: {type: string}}} schema
 * that a Map return produces. A generic schema makes downstream
 * TypeScript code generators emit a bare {@code Record<string, string>}
 * type; a typed record produces a proper interface with the exact
 * property names.
 *
 * <h3>Why a record and not a POJO</h3>
 *
 * <p>Records (Java 14+; required on Java 21) are the canonical
 * immutable-data-transfer type. They are self-documenting, immutable
 * by construction, and first-class for Jackson / springdoc — both tools
 * recognize record components and emit named properties from them.
 * For a SPIKE-only response type they are strictly less code than a
 * hand-written POJO.
 *
 * <h3>SPIKE-only</h3>
 *
 * <p>This type lives under
 * {@code com.aistudy.server.spike.openapi.model}, deliberately below
 * the SPIKE package boundary. It is not a production response DTO and
 * must NOT be moved into a non-spike package or reused by any business
 * module. Once a real system-health endpoint is introduced, this
 * record will be deleted along with the SPIKE health controller that
 * consumes it.
 *
 * <h3>Field semantics</h3>
 *
 * <ul>
 *   <li>{@code status}: literal
 *       {@code "UP"} — the SPIKE health signal. Not derived from any
 *       real health indicator; this is the minimal "the server is
 *       responding" contract.</li>
 *   <li>{@code service}: literal
 *       {@code "AIStudyServer"} — the identity of the responding
 *       process. Hard-coded for the SPIKE.</li>
 *   <li>{@code phase}: literal
 *       {@code "SPIKE-001"} — the SPIKE that originally introduced
 *       the endpoint. Kept as a constant marker for tooling that
 *       wants to know which SPIKE owns the endpoint. Not updated to
 *       the current SPIKE number (SPIKE-005) to keep this record
 *       stable across future MICROs.</li>
 * </ul>
 */
public record SpikeHealthResponse(
        String status,
        String service,
        String phase
) {
}
