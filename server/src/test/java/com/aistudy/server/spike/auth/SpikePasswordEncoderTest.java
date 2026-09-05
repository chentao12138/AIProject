package com.aistudy.server.spike.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE-004 MICRO-03A — PasswordEncoder contract test.
 *
 * Verifies that the {@link PasswordEncoder} bean exposed by
 * {@link SpikeSecurityConfig} behaves like a real password hasher:
 *
 *   1. encode(raw) != raw                 (no plaintext passthrough)
 *   2. matches(raw, encoded) == true      (correct password verifies)
 *   3. matches(other, encoded) == false   (wrong password is rejected)
 *
 * We deliberately do NOT assert that two consecutive encode() calls return the
 * same string. BCrypt uses a random per-hash salt by design, so the same
 * plaintext typically yields a different ciphertext every time. Hard-coding
 * an expected BCrypt string here would also couple the test to a specific
 * work-factor / library version and would go stale as soon as Spring Security
 * bumps the default.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpikePasswordEncoderTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * MICRO-08A: mock the SPIKE-only membership repository so this
     * BCrypt-focused test does not need a real {@code JdbcTemplate}
     * or {@code DataSource} bean on the classpath.
     *
     * <h3>Why this mock is required</h3>
     *
     * The {@code test} profile (see {@code application-test.yml})
     * deliberately excludes {@code DataSourceAutoConfiguration} so
     * unit-oriented tests run without a database. Since MICRO-07E-A
     * wired {@link SpikeSpaceAccess} to constructor-inject
     * {@link SpikeSpaceMembershipRepository}, and the repository
     * constructor-injects {@code JdbcTemplate}, the full application
     * context fails to boot under this profile with:
     * <pre>
     *   No qualifying bean of type
     *     'org.springframework.jdbc.core.JdbcTemplate'
     * </pre>
     *
     * Replacing the repository bean with a Mockito mock via
     * {@code @MockitoBean} prevents Spring from ever calling the
     * real repository's constructor, so the missing
     * {@code JdbcTemplate} dependency is never resolved.
     *
     * <h3>Why not stub the mock in this test</h3>
     *
     * This class only tests
     * {@link PasswordEncoder#encode(String)} and
     * {@link PasswordEncoder#matches(String, String)} — it has no
     * business calling {@link SpikeSpaceAccess} or its repository.
     * No {@code when(...)}, {@code given(...)}, or {@code verify(...)}
     * calls are added. The mock exists purely to keep the Spring
     * context bootable.
     *
     * <h3>Why not change the profile</h3>
     *
     * This test was written before MICRO-07D-A added the repository,
     * so it was already correct under the {@code test} profile.
     * Switching to a database-backed profile would force this
     * BCrypt smoke test to stand up a real MySQL, which is the
     * wrong test for that. The repository-to-database path is
     * owned by
     * {@code SpikeSpaceMembershipIntegrationTest} and
     * {@code SpikeSpaceAuthorizationEndToEndIntegrationTest}.
     */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    @Test
    void encoderRoundTripsCorrectPasswordAndRejectsWrongPassword() {
        String raw = "TestPassword-123!";

        String encoded = passwordEncoder.encode(raw);

        // 1. Encoded must not be the plaintext (BCrypt produces a $2a$-prefixed
        //    hash with embedded salt).
        assertNotEquals(raw, encoded,
                "encoded password must differ from plaintext");

        // 2. The original plaintext must match the encoded form.
        assertTrue(passwordEncoder.matches(raw, encoded),
                "correct plaintext must match the encoded password");

        // 3. A different plaintext must not match the encoded form.
        assertFalse(passwordEncoder.matches("WrongPassword", encoded),
                "wrong plaintext must NOT match the encoded password");
    }
}
