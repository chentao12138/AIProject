package com.aistudy.server.spike;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * SPIKE-001 only. Minimal health endpoint to verify Spring Boot + HTTP stack works.
 * Not a business endpoint; will be replaced/removed by real system health after SPIKE-004+.
 */
@RestController
public class SpikeHealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "AIStudyServer",
                "phase", "SPIKE-001",
                "timestamp", Instant.now().toString()
        ));
    }
}
