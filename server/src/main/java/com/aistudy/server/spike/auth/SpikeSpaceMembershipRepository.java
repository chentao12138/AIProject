package com.aistudy.server.spike.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SPIKE-004 MICRO-07D-A — SPIKE-only repository for the
 * {@code spike_space_membership} table created by V003.
 *
 * <p><strong>SPIKE ONLY. NOT a production User / LearningSpace
 * Membership Repository.</strong></p>
 *
 * <h3>Why this class exists</h3>
 *
 * {@link SpikeSpaceMembershipIntegrationTest} (MICRO-07C-A /
 * MICRO-07C-B) already proved, on a real MySQL instance with real
 * Flyway migrations applied, that the following SQL correctly answers
 * the membership question for the three relevant states:
 *
 * <pre>
 *   ACTIVE membership row exists       → true
 *   non-ACTIVE membership row exists   → false (e.g. status = REVOKED)
 *   membership row is missing          → false
 * </pre>
 *
 * Before this MICRO, that SQL lived inside the test class as a
 * private helper and could not be reused by production code. This
 * class promotes that exact SQL into a Spring bean so the next
 * MICRO can wire it into {@link SpikeSpaceAccess} without having to
 * duplicate the query string in two places.
 *
 * <h3>SQL under test</h3>
 *
 * <pre>
 * SELECT COUNT(*)
 *   FROM spike_space_membership
 *  WHERE user_subject = ?
 *    AND space_id     = ?
 *    AND status       = 'ACTIVE'
 * </pre>
 *
 * The SQL is parameterized — no string concatenation of caller input.
 * Only {@code user_subject} and {@code space_id} are parameters;
 * the status filter is a literal because this repository only answers
 * the "ACTIVE" question — that is its single responsibility.
 *
 * <h3>Return semantics</h3>
 *
 * {@link #hasActiveMembership(String, String)} returns {@code true}
 * iff at least one row matches all three predicates. This is
 * deliberately not "is the count exactly 1": the V003 schema does
 * not guarantee uniqueness on {@code (user_subject, space_id,
 * status)}, so multiple ACTIVE rows (from a race or a bug) must
 * still grant access rather than fail. A future production schema
 * with a {@code UNIQUE(user_subject, space_id, status)} constraint
 * can tighten this to "count == 1" if it wants to detect duplicates
 * as an error.
 *
 * <h3>What this class deliberately does NOT do</h3>
 *
 * <ul>
 *   <li>It does NOT expose non-ACTIVE lookups. Adding
 *       {@code hasAnyMembership} / {@code hasRevokedMembership}
 *       would violate the single-responsibility principle for a
 *       class that is intended to be replaced by the real
 *       membership repository.</li>
 *   <li>It does NOT manage lifecycle — no INSERT, no UPDATE, no
 *       DELETE, no TRUNCATE, no Flyway invocation. The SPIKE test
 *       that owns the fixture lifecycle manages it directly via
 *       {@link JdbcTemplate}.</li>
 *   <li>It does NOT cache. Each call hits the database. Caching
 *       is a production concern and is deliberately deferred so
 *       that this SPIKE class cannot accidentally mask a race
 *       condition in the test.</li>
 *   <li>It does NOT touch {@link SpikeSpaceAccess} directly — the
 *       wiring is deferred to the next MICRO. This keeps the
 *       current SPIKE-004 progress reproducible: if the wiring
 *       later fails, we can still run the integration test against
 *       the hard-coded rule to bisect the fault.</li>
 * </ul>
 *
 * <h3>Injection style</h3>
 *
 * Constructor injection of {@link JdbcTemplate}. No field injection,
 * no setter injection. This matches the modern Spring idiom and
 * makes the class trivially testable with a
 * {@link org.springframework.jdbc.core.JdbcTemplate} mock if a
 * unit test is ever added (it is not added in this MICRO).
 *
 * <h3>Bean name</h3>
 *
 * The default Spring bean name is {@code spikeSpaceMembershipRepository}
 * (lower-cased first letter of the class name). A future MICRO that
 * wires this bean into {@link SpikeSpaceAccess} can reference it by
 * constructor injection without needing a {@code @Qualifier}.
 */
@Repository
public class SpikeSpaceMembershipRepository {

    /**
     * The membership lookup SQL. Kept as a class constant so that
     * (a) the SQL is defined exactly once and (b) it is greppable
     * as a literal for future schema migrations.
     *
     * <p>Do NOT refactor this to a formatted/parameterized template
     * without going through MICRO-07D-A's SQL-semantics guard. The
     * current literal is what
     * {@link SpikeSpaceMembershipIntegrationTest} verified against
     * a real MySQL instance; changing the SQL text would require
     * re-running the test.</p>
     */
    private static final String ACTIVE_MEMBERSHIP_QUERY =
            "SELECT COUNT(*) FROM spike_space_membership "
                    + "WHERE user_subject = ? AND space_id = ? AND status = 'ACTIVE'";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor injection of {@link JdbcTemplate}. Spring will
     * provide the primary {@code JdbcTemplate} bean for the
     * configured {@code DataSource}; no custom configuration is
     * required.
     *
     * @param jdbcTemplate the JDBC template to use for queries
     *        against the membership table. Must not be {@code null}.
     */
    public SpikeSpaceMembershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Answers the single SPIKE question: "does the given user have an
     * ACTIVE membership for the given space?"
     *
     * <p>The SQL executed is exactly the SQL that
     * {@link SpikeSpaceMembershipIntegrationTest} has already
     * verified against a real MySQL instance running migrations up
     * to V003.</p>
     *
     * @param userSubject the subject identifier of the authenticated
     *        caller (typically the JWT {@code sub} claim). Must not
     *        be {@code null}; a {@code null} subject is treated as
     *        an unmatchable parameter and will return {@code false}
     *        from the SQL.
     * @param spaceId     the space identifier from the request path.
     *        Must not be {@code null} for the same reason as
     *        {@code userSubject}.
     * @return {@code true} if and only if at least one row in
     *         {@code spike_space_membership} has
     *         {@code user_subject = userSubject}
     *         AND {@code space_id = spaceId}
     *         AND {@code status = 'ACTIVE'}.
     *         Returns {@code false} for a non-matching pair, for
     *         pairs that only have non-ACTIVE rows (e.g. REVOKED),
     *         and for {@code null} query results (which in practice
     *         cannot happen because {@code COUNT(*)} always returns
     *         a row, but the null-guard is retained for defensive
     *         correctness).
     */
    public boolean hasActiveMembership(String userSubject, String spaceId) {
        Long count = jdbcTemplate.queryForObject(
                ACTIVE_MEMBERSHIP_QUERY, Long.class, userSubject, spaceId);
        return count != null && count > 0;
    }
}
