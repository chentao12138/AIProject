package com.aistudy.server.spike.auth;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * SPIKE-004 MICRO-07E-A — Space Access decision bean backed by a real
 * database lookup through {@link SpikeSpaceMembershipRepository}.
 *
 * <p><strong>SPIKE ONLY. NOT a production User / LearningSpace
 * Membership implementation.</strong></p>
 *
 * <h3>History</h3>
 *
 * Before this MICRO, this class was hard-coded to a single literal
 * rule ({@code spike-user-1 + space-A → true}), which was sufficient
 * to prove Spring Method Security's {@code @PreAuthorize} wiring works
 * end-to-end (MICRO-06B-A / MICRO-06C-A). MICRO-07D-A introduced a
 * {@code SpikeSpaceMembershipRepository} backed by the V003
 * {@code spike_space_membership} table; MICRO-07C-B verified its SQL
 * against a real MySQL instance. MICRO-07D-B mocked that repository
 * out of the Security / HTTP boundary test context so it did not
 * require a database. This MICRO wires the repository into
 * {@code canAccess} for the first time, closing the loop:
 *
 * <pre>
 *   Authentication.getName()
 *       + path variable spaceId
 *       ↓
 *   SpikeSpaceMembershipRepository.hasActiveMembership(...)
 *       ↓
 *   spike_space_membership table (status = 'ACTIVE')
 *       ↓
 *   true / false
 * </pre>
 *
 * <h3>What this class does</h3>
 *
 * <ol>
 *   <li>Defensive null / authentication checks — any missing input
 *       (null {@code Authentication}, unauthenticated, null principal
 *       name, null {@code spaceId}) returns {@code false}.</li>
 *   <li>Delegates the actual membership decision to
 *       {@link SpikeSpaceMembershipRepository#hasActiveMembership(String, String)}.
 *       That is the ONLY place the SQL lives; this class does not
 *       touch JDBC, JPA, SQL, or any persistence layer directly.</li>
 * </ol>
 *
 * <h3>Fail-closed, not fail-open</h3>
 *
 * If the repository throws (e.g. a MySQL connection failure, a SQL
 * exception, a driver error), the exception propagates upward. It is
 * NOT caught and returned as {@code false} (deny-by-default on
 * infrastructure failure is a deliberate security choice) and — more
 * importantly — it is NOT caught and returned as {@code true}
 * (fail-open would let an attacker exploit a broken database to
 * gain access). Spring Method Security will surface the exception
 * to the HTTP layer; the caller sees 500, not 200. That is the
 * correct SPIKE behavior: if the underlying membership query cannot
 * be answered, the request is denied with an error, not silently
 * allowed.
 *
 * <h3>What this class deliberately does NOT do</h3>
 *
 * <ul>
 *   <li>It does NOT read a JWT claim or a role. {@code Authentication.getName()}
 *       is the canonical subject identifier supplied by the Resource
 *       Server from the JWT's {@code sub} claim; this class uses it
 *       as an opaque string. No role / authority / permission check.</li>
 *   <li>It does NOT cache. Every call hits the repository, which hits
 *       the database. Caching is a production concern; adding it here
 *       would mask a race condition between the fixture INSERT and the
 *       membership lookup in the SPIKE integration test.</li>
 *   <li>It does NOT consult multiple tables, a LearningSpace table,
 *       a User table, or a Permission table. The SPIKE's whole point
 *       is a single table with three columns.</li>
 *   <li>It does NOT translate the subject through a User entity.
 *       {@code authentication.getName()} is used directly as the
 *       {@code user_subject} value — the Resource Server already
 *       produced it from the JWT's {@code sub} claim.</li>
 *   <li>It does NOT fail-open on repository exceptions. See above.</li>
 * </ul>
 *
 * <h3>Bean name</h3>
 *
 * The default Spring bean name is {@code spikeSpaceAccess} (lower-cased
 * first letter of the class name). The {@code @PreAuthorize} SpEL
 * expression on
 * {@link SpikeProtectedController#spaceEndpoint(String)} references
 * this bean by that exact name; renaming this class would break the
 * SpEL reference.
 *
 * <h3>Injection style</h3>
 *
 * Constructor injection of
 * {@link SpikeSpaceMembershipRepository}. No field injection, no
 * setter injection. Spring's default bean resolution will supply the
 * {@code @Repository} bean produced by component scanning;
 * {@code SpikeSecurityBoundaryTest} replaces it with a Mockito mock
 * via {@code @MockitoBean} to avoid a database dependency for the
 * Security / HTTP boundary tests.
 */
@Component
public class SpikeSpaceAccess {

    private final SpikeSpaceMembershipRepository membershipRepository;

    /**
     * Constructor injection of the membership repository. Spring will
     * provide the primary
     * {@link SpikeSpaceMembershipRepository} bean (backed by real
     * MySQL in production and by the SPIKE integration-test fixture
     * in tests); unit / boundary tests may substitute a Mockito mock
     * via {@code @MockitoBean} without changing this constructor.
     *
     * @param membershipRepository the repository used to answer the
     *        "does this (subject, spaceId) pair have an ACTIVE
     *        membership?" question. Must not be {@code null}; passing
     *        a {@code null} repository here would defeat the whole
     *        purpose of wiring a database-backed authorization check.
     *        Runtime behavior on a {@code null} repository is a
     *        {@code NullPointerException} from the delegation — the
     *        caller sees that as a 500, not a silent {@code false}.
     */
    public SpikeSpaceAccess(
            SpikeSpaceMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    /**
     * Decides whether the given {@link Authentication} is allowed to
     * access the given {@code spaceId}.
     *
     * <p>The decision is made in two phases:</p>
     *
     * <h4>Phase 1 — defensive checks (fail closed)</h4>
     *
     * Any of these conditions returns {@code false} immediately,
     * before touching the repository:
     * <ul>
     *   <li>{@code authentication == null} — no identity at all; deny.</li>
     *   <li>{@code !authentication.isAuthenticated()} — identity
     *       present but not authenticated (e.g. anonymous); deny.</li>
     *   <li>{@code authentication.getName() == null} — an
     *       authenticated object without a principal name; deny.
     *       This is a defensive guard: the JWT path should always
     *       produce a non-null {@code getName()}, but the SpEL
     *       invocation can pass a null principal in degenerate cases.</li>
     *   <li>{@code spaceId == null} — the request did not supply a
     *       space identifier; deny. Spring MVC path-variable
     *       resolution normally prevents a null {@code spaceId} from
     *       reaching this method, but the guard is retained so a
     *       malformed invocation cannot accidentally grant access.</li>
     * </ul>
     *
     * <h4>Phase 2 — repository delegation (do not catch)</h4>
     *
     * When all defensive checks pass, this method delegates to
     * {@link SpikeSpaceMembershipRepository#hasActiveMembership(String, String)},
     * passing the JWT subject and the request-supplied space id.
     *
     * <p>The repository's return value is returned as-is. In
     * particular, if the repository throws (database down, SQL error,
     * driver exception), the exception propagates. This method does
     * NOT catch and convert it to {@code true} (fail-open would let
     * a broken database become a bypass) and it does NOT catch and
     * convert it to {@code false} (that would mask an infrastructure
     * failure as if the caller were correctly denied — the HTTP
     * layer would silently return 403 for a 500 condition). Spring
     * Method Security's exception handler surfaces the exception to
     * the caller as a 5xx, which is the honest, correct behavior for
     * a broken membership backend.</p>
     *
     * @param authentication the authentication object present in the
     *        {@code SecurityContext} at the time the guarded
     *        controller method is invoked. May be {@code null} in
     *        degenerate cases.
     * @param spaceId        the space identifier supplied via the
     *        {@code /api/v1/spike/spaces/{spaceId}} path variable.
     *        May be {@code null} in degenerate cases.
     * @return {@code true} iff the defensive checks pass AND the
     *         repository reports an ACTIVE membership for the given
     *         {@code (authentication.getName(), spaceId)} pair.
     *         {@code false} in every other case — including null
     *         inputs, unauthenticated callers, and (via the
     *         repository) non-matching or non-ACTIVE database rows.
     * @throws RuntimeException if the repository itself throws —
     *         NOT caught here, NOT converted to a boolean. See the
     *         fail-closed discussion in the class Javadoc.
     */
    public boolean canAccess(Authentication authentication, String spaceId) {
        if (authentication == null) {
            return false;
        }
        if (!authentication.isAuthenticated()) {
            return false;
        }
        String name = authentication.getName();
        if (name == null) {
            return false;
        }
        if (spaceId == null) {
            return false;
        }
        return membershipRepository.hasActiveMembership(name, spaceId);
    }
}
