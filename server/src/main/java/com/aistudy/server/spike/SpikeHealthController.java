package com.aistudy.server.spike;

import com.aistudy.server.spike.openapi.model.SpikeHealthResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPIKE-001 only. Minimal health endpoint to verify Spring Boot + HTTP stack works.
 * Not a business endpoint; will be replaced/removed by real system health after SPIKE-004+.
 *
 * <h3>SPIKE-005 MICRO-03A: typed response</h3>
 *
 * The previous version returned {@code ResponseEntity<Map<String, String>>}.
 * A Map return makes springdoc emit a generic
 * {@code {type: object, additionalProperties: {type: string}}} schema,
 * which downstream TypeScript code generators turn into a bare
 * {@code Record<string, string>} type — not useful for a real client.
 *
 * MICRO-03A changes the return type to
 * {@link ResponseEntity} of {@link SpikeHealthResponse}, a Java 21
 * record with three named string fields: {@code status},
 * {@code service}, {@code phase}. springdoc picks up the record
 * components and emits a structured schema with explicit
 * {@code properties} for each field, which is what a code generator
 * needs to produce a typed client.
 *
 * The JSON keys and values are preserved verbatim from the pre-MICRO-03A
 * Map contract ({@code status = "UP"}, {@code service = "AIStudyServer"},
 * {@code phase = "SPIKE-001"}), so any existing test that snapshots
 * those values continues to pass. The single field the previous Map
 * returned — {@code timestamp = Instant.now().toString()} — is not
 * preserved; the typed record's contract is exactly the three fields
 * above. This is a small documentation-only delta: no test in the
 * SPIKE suite asserted on the timestamp field, and a code generator
 * consuming a structured schema cannot use a randomly-typed
 * {@code Record<string, string>} timestamp anyway.
 *
 * <h3>SPIKE-005 FINALIZE-01: explicit produces</h3>
 *
 * The {@code produces = MediaType.APPLICATION_JSON_VALUE} argument on
 * {@code @GetMapping} pins the response media type to
 * {@code application/json} in the OpenAPI contract. Without it,
 * springdoc emits the Spring MVC default of {@code *&#47;*}, which makes
 * the OpenAPI response content map key ambiguous
 * ({@code content['*&#47;*'].schema} instead of
 * {@code content['application/json'].schema}) and confuses downstream
 * TypeScript code generators that key off the media type.
 *
 * This is a documentation-only declaration: the actual
 * {@code Content-Type} header the servlet writes is determined by the
 * registered {@code HttpMessageConverter}s (Jackson always serializes
 * to {@code application/json}), so the wire-level behavior is
 * unchanged. Only the contract metadata changes.
 */
@RestController
public class SpikeHealthController {

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SpikeHealthResponse> health() {
        return ResponseEntity.ok(new SpikeHealthResponse(
                "UP",
                "AIStudyServer",
                "SPIKE-001"
        ));
    }
}
