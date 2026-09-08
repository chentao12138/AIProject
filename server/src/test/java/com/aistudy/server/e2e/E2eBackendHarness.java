package com.aistudy.server.e2e;

import com.aistudy.server.spike.auth.SpikeJwtTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * ELECTRON-CORS-001-C PART 1 — TEST-ONLY backend harness.
 *
 * <p>Starts the REAL backend on port 8080 under the {@code flyway-it}
 * profile (real MySQL {@code aistudy_flyway_test}) and keeps it alive
 * until the operator stops the process. Issues one short-lived JWT for
 * subject {@code desktop-e2e-user} through the SAME Spring context
 * ({@link SpikeJwtTokenService} + runtime SecretKey) and writes it to
 * {@code %TEMP%\aistudy-desktop-e2e-token.txt}.
 *
 * <p>The token keeps the existing 5-minute TTL — no TTL change, no
 * fixed production secret, no /dev/token endpoint, no login endpoint.
 * If the token expires mid-integration, re-run this harness for a
 * fresh one.
 *
 * <p>Deliberately NOT a {@code *Test} class name: the full Maven suite
 * never picks it up. It runs ONLY via
 * {@code -Dtest=E2eBackendHarness test}.
 *
 * <p>The token is never printed, never committed, never written to
 * docs; the token file is removed during PART 8 cleanup.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=8080")
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
public class E2eBackendHarness {

    /** The e2e subject used for every E2E-C-* record. */
    public static final String E2E_SUBJECT = "desktop-e2e-user";

    /** Token file location: %TEMP%\aistudy-desktop-e2e-token.txt */
    public static final String TOKEN_FILE_NAME = "aistudy-desktop-e2e-token.txt";

    @Autowired
    private SpikeJwtTokenService tokenService;

    @Test
    void keepAliveUntilStopped() throws Exception {
        String token = tokenService.issueAccessToken(E2E_SUBJECT);
        String tempDir = System.getenv("TEMP");
        Path tokenFile = Paths.get(tempDir, TOKEN_FILE_NAME);
        Files.writeString(tokenFile, token, StandardCharsets.UTF_8);
        System.out.println("[E2E-HARNESS] token written to " + tokenFile);
        System.out.println("[E2E-HARNESS] subject=" + E2E_SUBJECT
                + " backend=http://localhost:8080 keeping alive (stop me with Ctrl-C / taskkill)");
        new CountDownLatch(1).await();
    }
}
