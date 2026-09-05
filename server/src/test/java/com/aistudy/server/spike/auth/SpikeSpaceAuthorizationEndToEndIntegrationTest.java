package com.aistudy.server.spike.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPIKE-004 MICRO-07F-A — End-to-End Space Authorization integration test.
 *
 * <p><strong>SPIKE ONLY. NOT a production User / LearningSpace
 * Authorization integration test.</strong></p>
 *
 * <h3>What this class proves</h3>
 *
 * The full authorization chain runs against a real MySQL database
 * with real Flyway migrations, real Spring Security, real Spring
 * Method Security, and real HS256-signed JWTs — with NO mocks anywhere
 * in the path:
 *
 * <pre>
 *   SpikeJwtTokenService.issueAccessToken(...)
 *       ↓  (real HS256 JWT, 5-minute lifetime, subject in 'sub' claim)
 *   HTTP GET /api/v1/spike/spaces/{spaceId}
 *       Authorization: Bearer &lt;token&gt;
 *       ↓
 *   SecurityFilterChain
 *       ↓  (Resource Server decodes + verifies signature + exp)
 *   JwtDecoder  →  Authentication  (principal name = JWT sub claim)
 *       ↓
 *   @PreAuthorize("@spikeSpaceAccess.canAccess(authentication, #spaceId)")
 *       ↓
 *   SpikeSpaceAccess.canAccess(authentication, spaceId)
 *       ↓  (null-guard + delegate)
 *   SpikeSpaceMembershipRepository.hasActiveMembership(sub, spaceId)
 *       ↓  (parameterized SQL)
 *   JdbcTemplate
 *       ↓
 *   MySQL spike_space_membership table
 *       ↓
 *   HTTP 200 or 403
 * </pre>
 *
 * <p>This is the only SPIKE-004 test that verifies the whole chain
 * end-to-end against a real database. The boundary test
 * ({@code SpikeSecurityBoundaryTest}, MICRO-07D-B / MICRO-07E-A)
 * mocks the repository to keep the Security-only tests fast and
 * database-free. The unit-oriented integration test
 * ({@code SpikeSpaceMembershipIntegrationTest}, MICRO-07C-B)
 * tests the repository in isolation. This class — MICRO-07F-A —
 * is the join point that says "when all of these are wired together,
 * the answer for a real user/space pair is 200 for ACTIVE, 403 for
 * REVOKED, 403 for missing."</p>
 *
 * <h3>Annotations</h3>
 *
 * <ul>
 *   <li>{@code @SpringBootTest} — full application context, all
 *       {@code @Component} / {@code @Repository} beans are real.</li>
 *   <li>{@code @AutoConfigureMockMvc} — enables {@link MockMvc}
 *       without starting a real HTTP server.</li>
 *   <li>{@code @ActiveProfiles("flyway-it")} — reads
 *       {@code application-flyway-it.yml}, which points the
 *       datasource at {@code aistudy_flyway_test} and disables
 *       Spring's auto-Flyway (this test drives Flyway explicitly so
 *       it can be re-run in any order).</li>
 *   <li>{@code @TestInstance(Lifecycle.PER_CLASS)} — allows
 *       non-static {@code @BeforeAll} (needed so the schema guard
 *       can use the injected {@code JdbcTemplate}).</li>
 * </ul>
 *
 * <h3>Mockito policy: NONE</h3>
 *
 * This class uses no Mockito at all. No {@code @MockitoBean}, no
 * {@code @Mock}, no {@code @InjectMocks}, no manual
 * {@code Mockito.mock(...)}. All Spring beans — including
 * {@link SpikeSpaceMembershipRepository} — are the real Spring-
 * managed instances wired by component scanning and the injected
 * {@code JdbcTemplate}.
 *
 * <h3>Database guard</h3>
 *
 * Before any test activity, the class queries {@code SELECT DATABASE()}
 * and refuses to run unless the result equals
 * {@code aistudy_flyway_test} exactly. Only {@code String.equals}
 * is used — never {@code contains} / {@code startsWith} /
 * {@code endsWith}.
 *
 * <h3>Migration bootstrap</h3>
 *
 * Every test method starts with an idempotent {@code Flyway.migrate()}.
 * Empty / older schema migrates to V003; already V003 is a no-op.
 * {@code cleanDisabled(true)} is set defensively so a future
 * refactor cannot accidentally call {@code Flyway.clean()} from
 * this code path.
 *
 * <h3>Fixture lifecycle</h3>
 *
 * Every test method runs:
 * <ol>
 *   <li>Schema guard.</li>
 *   <li>Flyway migrate (idempotent).</li>
 *   <li>Scoped DELETE of SPIKE-e2e rows.</li>
 *   <li>INSERT ACTIVE fixture:
 *       {@code spike-e2e-user-1 + space-e2e-A}.</li>
 *   <li>INSERT REVOKED fixture:
 *       {@code spike-e2e-user-1 + space-e2e-B}.</li>
 * </ol>
 * {@code spike-e2e-user-2} is deliberately NOT inserted — Test 3
 * verifies the "missing membership" case. All DELETEs are scoped to
 * {@code user_subject IN ('spike-e2e-user-1', 'spike-e2e-user-2')};
 * no unqualified DELETE, no TRUNCATE, no DROP, no Flyway.clean().
 * {@code created_at} uses {@code CURRENT_TIMESTAMP(6)} — never a
 * fixed time.
 *
 * <h3>Three tests</h3>
 *
 * <ol>
 *   <li>{@code activeMembershipReturns200} — subject
 *       {@code spike-e2e-user-1}, path {@code /api/v1/spike/spaces/space-e2e-A}
 *       → HTTP 200 with {@code {"spaceId":"space-e2e-A","status":"AUTHORIZED"}}.</li>
 *   <li>{@code revokedMembershipReturns403} — subject
 *       {@code spike-e2e-user-1}, path {@code /api/v1/spike/spaces/space-e2e-B}
 *       → HTTP 403 (row exists but status='REVOKED'; WHERE clause
 *       filters it out).</li>
 *   <li>{@code missingMembershipReturns403} — subject
 *       {@code spike-e2e-user-2}, path {@code /api/v1/spike/spaces/space-e2e-A}
 *       → HTTP 403 (no row for spike-e2e-user-2 in this table).</li>
 * </ol>
 *
 * <p>No {@code Mockito.verify} and no {@code Mockito.when} — this is
 * an end-to-end test; we observe only the HTTP outcome.</p>
 *
 * <h3>Deliberate non-goals</h3>
 *
 * <ul>
 *   <li>No JWT claim inspection. The Resource Server's decoding is
 *       trusted; we only verify that a subject produced by
 *       {@code SpikeJwtTokenService.issueAccessToken} reaches
 *       {@code SpikeSpaceMembershipRepository.hasActiveMembership}
 *       end-to-end via the HTTP layer.</li>
 *   <li>No direct call to {@link SpikeSpaceAccess#canAccess}.
 *       The controller entry point is the only API used.</li>
 *   <li>No {@code spike-e2e-user-2 + space-e2e-B} — that would
 *       also be 403 but would duplicate Test 3's identity-mismatch
 *       coverage without adding new signal.</li>
 *   <li>No HTTP 401 case — that would require an invalid token or
 *       an anonymous caller, both already covered by
 *       {@code SpikeSecurityBoundaryTest}.</li>
 *   <li>No learning-space entity, user entity, permission entity,
 *       or role — SPIKE uses opaque string identifiers only.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpikeSpaceAuthorizationEndToEndIntegrationTest {

    /**
     * The ONLY database this test is allowed to run against. Any
     * other {@code DATABASE()} value aborts the class at guard time.
     */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /**
     * SPIKE-e2e-only user subjects used by this test. Cleanup DELETE
     * is scoped to this exact list — never broader.
     */
    private static final List<String> SPIKE_E2E_ONLY_USERS = List.of(
            "spike-e2e-user-1",
            "spike-e2e-user-2"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpikeJwtTokenService spikeJwtTokenService;

    // ==================== @BeforeAll ====================

    /**
     * Class-level schema guard. Runs once before any test method.
     * Only {@code equals} — no contains / startsWith / endsWith.
     */
    @BeforeAll
    void guardTargetDatabase() {
        assertSchemaIsFlywayTest();
    }

    // ==================== @BeforeEach ====================

    /**
     * Single per-test-method preparation. All steps execute in strict
     * textual order inside this one method — we deliberately do NOT
     * use multiple {@code @BeforeEach} methods sorted by {@code @Order}
     * (that ordering is not reliably guaranteed by JUnit 5 semantics,
     * and it caused a runtime failure in MICRO-07C-B).
     *
     * Steps, in this order:
     * <ol>
     *   <li>Schema guard — refuse to continue unless
     *       {@code DATABASE()} equals {@code aistudy_flyway_test}.</li>
     *   <li>{@code Flyway.migrate()} — idempotent; empty/older → V003,
     *       already latest → no-op. {@code cleanDisabled(true)} is a
     *       defensive setting so a future refactor cannot accidentally
     *       clean the wrong schema from this code path.</li>
     *   <li>Scoped DELETE of SPIKE-e2e residue.</li>
     *   <li>INSERT ACTIVE fixture: {@code spike-e2e-user-1 + space-e2e-A}.</li>
     *   <li>INSERT REVOKED fixture: {@code spike-e2e-user-1 + space-e2e-B}.</li>
     * </ol>
     *
     * {@code spike-e2e-user-2} is deliberately NOT inserted — Test 3
     * verifies the "missing membership" case.
     */
    @BeforeEach
    void prepareDatabase() {
        // (1) Schema guard.
        assertSchemaIsFlywayTest();

        // (2) Flyway migrate (idempotent).
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();

        // (3) Scoped DELETE of residue.
        cleanSpikeE2ERows();

        // (4) Fixture 1: ACTIVE membership for user-1 + space-e2e-A.
        jdbcTemplate.update(
                "INSERT INTO spike_space_membership "
                        + "(user_subject, space_id, status, created_at) "
                        + "VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6))",
                "spike-e2e-user-1", "space-e2e-A");

        // (5) Fixture 2: REVOKED membership for user-1 + space-e2e-B.
        jdbcTemplate.update(
                "INSERT INTO spike_space_membership "
                        + "(user_subject, space_id, status, created_at) "
                        + "VALUES (?, ?, 'REVOKED', CURRENT_TIMESTAMP(6))",
                "spike-e2e-user-1", "space-e2e-B");

        // Fixture 3 (spike-e2e-user-2) deliberately NOT inserted.
    }

    // ==================== @AfterEach ====================

    /**
     * Cleanup after each test. Same scoped DELETE — never broader.
     */
    @AfterEach
    void cleanSpikeE2ERowsAfterTest() {
        cleanSpikeE2ERows();
    }

    // ==================== Tests ====================

    /**
     * Test 1: a valid Bearer JWT for {@code spike-e2e-user-1} against
     * {@code GET /api/v1/spike/spaces/space-e2e-A} — where an ACTIVE
     * membership row exists in {@code spike_space_membership} — must
     * return HTTP 200 with the expected JSON body.
     *
     * This is the allow-side of the end-to-end gate. The 200 proves
     * the whole chain works:
     * <ol>
     *   <li>{@link SpikeJwtTokenService#issueAccessToken(String)}
     *       signs a real HS256 JWT with {@code sub = "spike-e2e-user-1"}
     *       and a fresh 5-minute expiry.</li>
     *   <li>Spring Security's Resource Server decodes and verifies the
     *       JWT via the shared {@code JwtDecoder} bean, producing an
     *       {@code Authentication} whose {@code getName()} returns
     *       {@code spike-e2e-user-1}.</li>
     *   <li>{@code @PreAuthorize("@spikeSpaceAccess.canAccess(...)")}
     *       evaluates to {@code true} via the real Spring Method
     *       Security chain.</li>
     *   <li>{@link SpikeSpaceAccess#canAccess} delegates to the REAL
     *       {@link SpikeSpaceMembershipRepository} bean.</li>
     *   <li>The repository runs the parameterized SQL against a real
     *       MySQL connection (no H2, no mock, no fake DataSource) and
     *       finds {@code count = 1} for the ACTIVE fixture row.</li>
     *   <li>The controller produces the expected JSON response.</li>
     * </ol>
     */
    @Test
    void activeMembershipReturns200() throws Exception {
        String token = spikeJwtTokenService.issueAccessToken("spike-e2e-user-1");
        assertNotNull(token, "SpikeJwtTokenService must issue a non-null token");

        mockMvc.perform(get("/api/v1/spike/spaces/space-e2e-A")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceId").value("space-e2e-A"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    /**
     * Test 2: a valid Bearer JWT for {@code spike-e2e-user-1} against
     * {@code GET /api/v1/spike/spaces/space-e2e-B} — where a
     * membership row exists but its {@code status = 'REVOKED'} — must
     * return HTTP 403.
     *
     * This is the status-filter half of the end-to-end gate. The 403
     * proves that the repository's {@code WHERE status = 'ACTIVE'}
     * filter is honored end-to-end: even though a membership row
     * exists for this (user, space) pair, the non-ACTIVE status
     * rejects it. If a future regression removed the {@code status = 'ACTIVE'}
     * predicate from the SQL, this test would flip to 200 and catch
     * the mistake.
     *
     * Crucially, the JWT used is cryptographically valid — the same
     * issuer, the same HS256 key, a fresh expiry. So the 403 cannot
     * be attributed to a 401-class authentication failure; it must
     * be attributed to authorization failure at the
     * {@code @PreAuthorize} layer.
     */
    @Test
    void revokedMembershipReturns403() throws Exception {
        String token = spikeJwtTokenService.issueAccessToken("spike-e2e-user-1");
        assertNotNull(token, "SpikeJwtTokenService must issue a non-null token");

        mockMvc.perform(get("/api/v1/spike/spaces/space-e2e-B")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /**
     * Test 3: a valid Bearer JWT for {@code spike-e2e-user-2} against
     * {@code GET /api/v1/spike/spaces/space-e2e-A} — where NO
     * membership row exists for this (user, space) pair — must return
     * HTTP 403.
     *
     * This is the missing-row half of the end-to-end gate. Together
     * with Test 2, it proves that the authorization decision depends
     * on both the JWT subject AND the request-supplied spaceId:
     * <pre>
     *   spike-e2e-user-1 + space-e2e-A  →  200  (Test 1)  // ACTIVE row exists
     *   spike-e2e-user-1 + space-e2e-B  →  403  (Test 2)  // row exists, REVOKED
     *   spike-e2e-user-2 + space-e2e-A  →  403  (Test 3)  // no row at all
     * </pre>
     * The same JWT-signing and HTTP chain runs in all three; the
     * decision varies only with the database state.
     *
     * The 403 (not 401) proves that spike-e2e-user-2's JWT decoded
     * and authenticated successfully — Spring Method Security reached
     * the {@code @PreAuthorize} layer, and it was that layer that
     * denied the request.
     */
    @Test
    void missingMembershipReturns403() throws Exception {
        String token = spikeJwtTokenService.issueAccessToken("spike-e2e-user-2");
        assertNotNull(token, "SpikeJwtTokenService must issue a non-null token");

        mockMvc.perform(get("/api/v1/spike/spaces/space-e2e-A")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ==================== helpers ====================

    /**
     * Schema guard. Uses {@code equals} only — no contains /
     * startsWith / endsWith, per MICRO-07C-A/B and MICRO-07F-A.
     *
     * @throws IllegalStateException if the current database is not
     *         exactly {@value #EXPECTED_SCHEMA}
     */
    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(actual, "SELECT DATABASE() returned null — refusing to proceed");
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run SpikeSpaceAuthorizationEndToEndIntegrationTest: "
                            + "expected schema '" + EXPECTED_SCHEMA
                            + "' but got '" + actual + "'. "
                            + "SPIKE-004 MICRO-07F-A MUST run against " + EXPECTED_SCHEMA
                            + " only. This is a safety guard to prevent accidentally "
                            + "touching aistudy_spike or any other database.");
        }
    }

    /**
     * Scoped cleanup: DELETE only rows belonging to this test class's
     * SPIKE-e2e-only user subjects. NEVER DELETEs without a WHERE
     * clause, NEVER TRUNCATEs, NEVER DROPs, NEVER calls
     * {@code Flyway.clean()}.
     */
    private void cleanSpikeE2ERows() {
        List<String> users = new ArrayList<>(SPIKE_E2E_ONLY_USERS);
        String placeholders = String.join(",",
                Collections.nCopies(users.size(), "?"));
        jdbcTemplate.update(
                "DELETE FROM spike_space_membership "
                        + "WHERE user_subject IN (" + placeholders + ")",
                users.toArray());
    }
}
