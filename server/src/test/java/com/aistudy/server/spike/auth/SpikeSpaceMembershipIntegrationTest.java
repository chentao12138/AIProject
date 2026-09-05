package com.aistudy.server.spike.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE-004 MICRO-07C-A — Self-contained Spring Boot integration test
 * that verifies the query semantics of the {@code spike_space_membership}
 * table backed by V003 on a real MySQL instance:
 *
 * <pre>
 *   ACTIVE membership exists       →  true
 *   non-ACTIVE membership exists   →  false   (e.g. status = REVOKED)
 *   membership row is missing      →  false
 * </pre>
 *
 * <h3>Scope and boundaries</h3>
 *
 * This test deliberately does NOT touch production code —
 * {@code SpikeSpaceAccess} still has its hard-coded SPIKE rule
 * ({@code spike-user-1 + space-A → true}). This MICRO only proves that
 * a database-backed membership lookup returns the correct boolean
 * for the three relevant states. The next MICRO will wire the DB
 * lookup into {@code SpikeSpaceAccess}.
 *
 * <h3>Target database guard</h3>
 *
 * Before ANY test activity, the class queries {@code SELECT DATABASE()}
 * and refuses to run unless the result equals {@code aistudy_flyway_test}
 * exactly. {@code contains} / {@code startsWith} / {@code endsWith} are
 * forbidden by MICRO-07C-A — only {@code String.equals} is used.
 *
 * <h3>Migration bootstrap</h3>
 *
 * After the guard passes, {@code Flyway.migrate()} is invoked once. It
 * is idempotent:
 * <ul>
 *   <li>empty / older schema → migrates to V003 (latest)</li>
 *   <li>already V003 → no-op</li>
 * </ul>
 *
 * {@code Flyway.clean()} is NEVER called from this class.
 * {@code cleanDisabled(true)} is set defensively so that even a
 * misbehaving future refactor cannot accidentally clean the wrong
 * database from this code path.
 *
 * <h3>Fixture data lifecycle</h3>
 *
 * Each test is completely independent — no cross-test state leaks.
 * <ul>
 *   <li>{@code @BeforeAll}: class-level schema guard (runs once, uses
 *       the injected {@code JdbcTemplate}, so the class is annotated
 *       with {@code @TestInstance(TestInstance.Lifecycle.PER_CLASS)})</li>
 *   <li>single {@code @BeforeEach prepareDatabase()} — five steps in
 *       strict textual order:
 *       <ol>
 *         <li>schema guard</li>
 *         <li>{@code Flyway.migrate()} (idempotent)</li>
 *         <li>scoped DELETE of SPIKE residue</li>
 *         <li>INSERT {@code spike-db-user-1 + space-db-A = ACTIVE}</li>
 *         <li>INSERT {@code spike-db-user-1 + space-db-B = REVOKED}</li>
 *       </ol>
 *       {@code spike-db-user-2} is deliberately NOT inserted.
 *       <p>
 *       We deliberately do NOT use multiple {@code @BeforeEach} methods
 *       sorted by {@code @Order} — that pattern caused a JUnit runtime
 *       failure in MICRO-07C-A and is not a reliable ordering
 *       mechanism.</p>
 *   </li>
 *   <li>{@code @AfterEach}: scoped DELETE — same WHERE clause</li>
 * </ul>
 *
 * All DELETEs are scoped to {@code user_subject IN ('spike-db-user-1',
 * 'spike-db-user-2')}. No unqualified {@code DELETE FROM
 * spike_space_membership}, no {@code TRUNCATE}, no {@code DROP}, no
 * {@code Flyway.clean()}.
 *
 * <h3>Query semantics</h3>
 *
 * The three tests no longer inline the membership SQL — they call
 * {@link SpikeSpaceMembershipRepository#hasActiveMembership(String, String)},
 * which is the production SPIKE repository promoted from the previously
 * inlined SQL by MICRO-07D-A. The SQL text itself lives in exactly one
 * place (the repository); it is not duplicated here.
 *
 * <p>Pre-MICRO-07D-A, the SQL lived in a private helper inside this
 * test class and could not be reused by production code. The next
 * MICRO after 07D-A will wire this repository into
 * {@link SpikeSpaceAccess}.</p>
 */
@SpringBootTest
@ActiveProfiles("flyway-it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpikeSpaceMembershipIntegrationTest {

    /**
     * The ONLY database this test is allowed to run against. Any other
     * DATABASE() value aborts the class at guard time.
     */
    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";

    /**
     * SPIKE-only user subjects used by this test. The cleanup DELETE
     * is scoped to this exact list — never broader.
     */
    private static final List<String> SPIKE_ONLY_USERS = List.of(
            "spike-db-user-1",
            "spike-db-user-2"
    );

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SpikeSpaceMembershipRepository repository;

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
     * textual order inside this one method — we do NOT rely on
     * {@code @Order} to sort multiple {@code @BeforeEach} lifecycle
     * methods (that ordering is not guaranteed by JUnit 5 semantics,
     * and it caused the MICRO-07C-A runtime failure).
     *
     * Steps, in this order:
     * <ol>
     *   <li>Schema guard — refuse to continue unless the live JDBC
     *       connection's {@code DATABASE()} equals {@code aistudy_flyway_test}.</li>
     *   <li>Flyway.migrate() — idempotent; empty/older → V003,
     *       already latest → no-op. {@code cleanDisabled(true)} is a
     *       defensive setting so a future refactor cannot accidentally
     *       clean the wrong schema from this code path.</li>
     *   <li>Scoped DELETE of any SPIKE residue — protects against
     *       residue from a previously-interrupted run of THIS class.</li>
     *   <li>INSERT ACTIVE fixture: {@code spike-db-user-1 + space-db-A}.</li>
     *   <li>INSERT REVOKED fixture: {@code spike-db-user-1 + space-db-B}.</li>
     * </ol>
     *
     * {@code spike-db-user-2} is deliberately NOT inserted — the
     * third test verifies the "missing membership" case.
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
        cleanSpikeRows();

        // (4) Fixture 1: ACTIVE membership for user-1 + space-db-A.
        jdbcTemplate.update(
                "INSERT INTO spike_space_membership "
                        + "(user_subject, space_id, status, created_at) "
                        + "VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6))",
                "spike-db-user-1", "space-db-A");

        // (5) Fixture 2: REVOKED membership for user-1 + space-db-B.
        jdbcTemplate.update(
                "INSERT INTO spike_space_membership "
                        + "(user_subject, space_id, status, created_at) "
                        + "VALUES (?, ?, 'REVOKED', CURRENT_TIMESTAMP(6))",
                "spike-db-user-1", "space-db-B");

        // Fixture 3 (spike-db-user-2) deliberately NOT inserted.
    }

    // ==================== @AfterEach ====================

    /**
     * Cleanup after each test. Same scoped DELETE — never broader.
     */
    @AfterEach
    void cleanSpikeRowsAfterTest() {
        cleanSpikeRows();
    }

    // ==================== Tests ====================

    @Test
    @Order(1)
    void activeMembershipExists() {
        // Query: spike-db-user-1 + space-db-A + status='ACTIVE'
        // Fixture row exists with status='ACTIVE' → count = 1 → true.
        boolean hasAccess = repository.hasActiveMembership("spike-db-user-1", "space-db-A");
        assertTrue(hasAccess,
                "spike-db-user-1 + space-db-A should have an ACTIVE membership → true");
    }

    @Test
    @Order(2)
    void revokedMembershipDoesNotGrantAccess() {
        // Query: spike-db-user-1 + space-db-B + status='ACTIVE'
        // Fixture row exists, but status='REVOKED'. The WHERE clause
        // filters it out → count = 0 → false. This proves the status
        // filter is part of the access decision — a stale non-ACTIVE
        // row does NOT grant access.
        boolean hasAccess = repository.hasActiveMembership("spike-db-user-1", "space-db-B");
        assertFalse(hasAccess,
                "spike-db-user-1 + space-db-B has a REVOKED membership → false "
                        + "(status filter rejects it)");
    }

    @Test
    @Order(3)
    void missingMembershipDoesNotGrantAccess() {
        // Query: spike-db-user-2 + space-db-A + status='ACTIVE'
        // No fixture row for spike-db-user-2 exists in this table.
        // count = 0 → false. This proves the "row not present" case is
        // rejected — even though space-db-A itself has an ACTIVE row
        // (for spike-db-user-1), the spaceId alone is NOT enough to
        // grant access. The (user_subject, space_id) pair must both
        // match.
        boolean hasAccess = repository.hasActiveMembership("spike-db-user-2", "space-db-A");
        assertFalse(hasAccess,
                "spike-db-user-2 + space-db-A has no membership row → false");
    }

    // ==================== helpers ====================

    /**
     * Schema guard. Uses {@code equals} only — no contains /
     * startsWith / endsWith, per MICRO-07C-A.
     *
     * @throws IllegalStateException if the current database is not
     *         exactly {@value #EXPECTED_SCHEMA}
     */
    private void assertSchemaIsFlywayTest() {
        String actual = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (actual == null || !EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException(
                    "Refusing to run SpikeSpaceMembershipIntegrationTest: expected schema '"
                            + EXPECTED_SCHEMA + "' but got '" + actual + "'. "
                            + "SPIKE-004 MICRO-07C-A MUST run against " + EXPECTED_SCHEMA
                            + " only. This is a safety guard to prevent accidentally "
                            + "touching aistudy_spike or any other database.");
        }
    }

    /**
     * Scoped cleanup: DELETE only rows belonging to this test class's
     * SPIKE-only user subjects. NEVER DELETEs without a WHERE clause,
     * NEVER TRUNCATEs, NEVER DROPs, NEVER calls Flyway.clean().
     */
    private void cleanSpikeRows() {
        List<String> users = new ArrayList<>(SPIKE_ONLY_USERS);
        String placeholders = String.join(",",
                Collections.nCopies(users.size(), "?"));
        jdbcTemplate.update(
                "DELETE FROM spike_space_membership "
                        + "WHERE user_subject IN (" + placeholders + ")",
                users.toArray());
    }
}
