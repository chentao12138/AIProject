package com.aistudy.server.spike.auth;

import com.aistudy.server.space.mapper.LearningSpaceMapper;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.practice.mapper.PracticeSessionMapper;
import com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper;
import com.aistudy.server.practice.mapper.PracticeAnswerMapper;
import com.aistudy.server.wrong.mapper.WrongQuestionMapper;
import com.aistudy.server.wrong.mapper.ReviewTaskMapper;
import com.aistudy.server.wrong.mapper.ReviewRecordMapper;
import com.aistudy.server.exam.mapper.ExamMapper;
import com.aistudy.server.exam.mapper.ExamPaperMapper;
import com.aistudy.server.exam.mapper.ExamQuestionMapper;
import com.aistudy.server.exam.mapper.ExamAttemptMapper;
import com.aistudy.server.exam.mapper.ExamAnswerMapper;
import com.aistudy.server.exam.mapper.ExamResultMapper;

/**
 * SPIKE-004 MICRO-02 + MICRO-05B-A + MICRO-05C-A + MICRO-05D-A + MICRO-06A
 * + MICRO-06B-A + MICRO-06C-A + SPIKE-005 MICRO-01B — API authentication
 * boundary test.
 *
 * Ten strict assertions:
 *   1. GET /health                          → HTTP 200 + status=UP (anonymous).
 *   2. GET /v3/api-docs                     → HTTP 200 + $.openapi + $.paths
 *                                                 present (anonymous,
 *                                                 SPIKE-005 MICRO-01B).
 *   3. GET /api/v1/spike/protected          → HTTP 401 (anonymous, no auth).
 *   4. GET /api/v1/spike/protected
 *        with Authorization: Bearer *** HS256 JWT&gt;
 *                                     → HTTP 200 + status=AUTHENTICATED.
 *   5. GET /api/v1/spike/protected
 *        with Authorization: Bearer *** HS256 JWT with a
 *        tampered signature segment&gt;
 *                                     → HTTP 401.
 *   6. GET /api/v1/spike/protected
 *        with Authorization: Bearer *** HS256 JWT with an
 *        expired exp claim&gt;
 *                                     → HTTP 401.
 *   7. GET /api/v1/spike/method-denied
 *        with Authorization: Bearer *** HS256 JWT&gt;
 *                                     → HTTP 403.
 *   8. GET /api/v1/spike/spaces/space-A
 *        with Authorization: Bearer *** HS256 JWT for spike-user-1&gt;
 *                                     → HTTP 200 + status=AUTHORIZED.
 *   9. GET /api/v1/spike/spaces/space-B
 *        with Authorization: Bearer *** same valid HS256 JWT for spike-user-1&gt;
 *                                     → HTTP 403.
 *  10. GET /api/v1/spike/spaces/space-A
 *        with Authorization: Bearer *** HS256 JWT for spike-user-2&gt;
 *                                     → HTTP 403.
 *
 * Assertions 1 and 3 are MICRO-02: the auth boundary must be deterministic
 * before we layer real authentication on top.
 *
 * Assertion 2 is SPIKE-005 MICRO-01B: the OpenAPI contract endpoint exposed
 * by springdoc ({@code /v3/api-docs}) must be reachable by an anonymous
 * caller and must return a valid OpenAPI JSON document (at minimum a
 * {@code openapi} version string and a {@code paths} map). This is the gate
 * for a later MICRO that will generate a TypeScript client from that JSON.
 * Only the JSON shape is asserted here — the full contract content (operation
 * ids, schemas, securitySchemes, bearer auth) is left to a later MICRO that
 * adds those annotations and configuration.
 *
 * Assertion 4 is MICRO-05B-A: after the Resource Server is wired in, a
 * valid Bearer JWT must be accepted as an {@code authenticated()} caller
 * without any User / Role / session / custom filter logic.
 *
 * Assertion 5 is MICRO-05C-A: after the Resource Server is wired in, a
 * Bearer JWT whose signature segment has been modified (header and payload
 * unchanged) must be rejected with exactly 401. This isolates the failure
 * root cause to signature verification — not malformed payload, not claim
 * parsing, not issuer / subject / exp failure.
 *
 * Assertion 6 is MICRO-05D-A: after the Resource Server is wired in, a
 * Bearer JWT whose signature is cryptographically valid but whose {@code exp}
 * claim is in the past must be rejected with exactly 401. This isolates
 * the failure root cause to expiration — not signature, not structure, not
 * issuer / subject.
 *
 * Assertion 7 is MICRO-06A: a request carrying a valid HS256 JWT to a
 * {@code @PreAuthorize("denyAll()")} endpoint must be rejected with exactly
 * 403, not 401. The 401 vs 403 distinction proves that:
 *   - the JWT authentication succeeded (otherwise it would be 401), AND
 *   - the method-level authorization denied the request.
 * This is the gate that a future Space / membership MICRO will build on.
 *
 * Assertions 8 and 9 are MICRO-06B-A: after a real (but hard-coded)
 * server-side Space Access decision function is wired into
 * {@code @PreAuthorize}, the same authenticated caller gets 200 for one
 * space and 403 for another. The 200/403 pair — driven by the same valid
 * JWT — proves that the authorization layer is truly driven by the
 * request-supplied {@code spaceId}, not by JWT claims, and that the same
 * successful authentication can produce either an allow or a deny.
 *
 * Assertion 10 is MICRO-06C-A: a DIFFERENT authenticated user (spike-user-2)
 * requesting the SAME space-A that spike-user-1 can access must be rejected
 * with 403. Together with tests 8 and 9, this proves that the Space
 * Authorization decision depends on BOTH the authentication identity AND the
 * request-supplied spaceId — not on the spaceId alone. That is the gate
 * for a future MICRO that will back {@link SpikeSpaceAccess} with a real
 * User ↔ LearningSpace membership table.
 *
 * The protected-endpoint 401 assertions (tests 3, 5, and 6) are intentionally
 * not "not 200": they are exactly 401 Unauthorized. The method-denied 403
 * assertion (test 7), the unauthorized-space 403 assertion (test 9), and the
 * wrong-user-authorized-space 403 assertion (test 10) are intentionally not
 * "not 200": they are exactly 403 Forbidden. All are contracts, not
 * approximations.
 *
 * The valid-Bearer assertion (test 4) uses a token freshly issued by
 * {@link SpikeJwtTokenService} through the SAME Spring context, so the
 * Resource Server has to decode and verify it via the shared
 * {@link org.springframework.security.oauth2.jwt.JwtDecoder} bean. We do
 * NOT mock a JwtAuthenticationToken or any Authentication object.
 *
 * The expired-Bearer assertion (test 6) uses the shared {@link JwtEncoder}
 * bean from the same Spring context to mint a token whose HMAC-SHA256
 * signature is cryptographically correct but whose {@code exp} is set to a
 * time strictly in the past. That way the decoder cannot reject the token
 * for a signature reason — the only remaining rejection cause is expiration.
 *
 * Wrong-issuer, wrong-algorithm, malformed, missing Bearer, empty Bearer,
 * role, permission, and spaceIds-as-claim cases are intentionally not
 * covered here — those are future MICROs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpikeSecurityBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpikeJwtTokenService tokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    /**
     * MICRO-07D-B: mock the SPIKE-only membership repository so this
     * Security / HTTP boundary test does not need a real {@code
     * JdbcTemplate} or {@code DataSource} bean on the classpath.
     *
     * <h3>Why mock instead of stubbing the JdbcTemplate</h3>
     *
     * {@link SpikeSpaceMembershipRepository} is a {@code @Repository}
     * whose constructor requires a {@code JdbcTemplate}. Since
     * MICRO-07D-A introduced that bean into the main source tree, every
     * {@code @SpringBootTest} test that loads the full application
     * context now has to be able to construct it.
     *
     * The {@code SpikeSpaceMembershipIntegrationTest} (MICRO-07C-B)
     * is the ONLY test that owns the "Repository → JdbcTemplate →
     * real MySQL" path; it runs under the {@code flyway-it} profile
     * against a real {@code aistudy_flyway_test} database.
     *
     * This test — {@code SpikeSecurityBoundaryTest} — runs under the
     * {@code test} profile with no database. Its job is exclusively
     * to verify JWT authentication → Spring Security → Method Security
     * → HTTP status codes. It must not be forced to stand up a real
     * database just to satisfy a bean Spring's context scanner happens
     * to be able to construct.
     *
     * <h3>Why {@code @MockitoBean} and not the older patterns</h3>
     *
     * {@code @MockitoBean} (Spring Framework 6.2 / Spring Boot 3.4+)
     * is the canonical replacement for the older
     * {@code @MockBean} / {@code @TestConfiguration} pattern. It is
     * declarative, works with the modern test-context override
     * mechanism, and lets the rest of the application context wire
     * normally. We deliberately do NOT use:
     * <ul>
     *   <li>{@code @Mock} + {@code @InjectMocks} — those are unit-test
     *       concerns and do not integrate with the Spring context.</li>
     *   <li>{@code Mockito.mock(...)} manual stubs — leaks the mock
     *       framework into the test body.</li>
     *   <li>A {@code @TestConfiguration} that produces a
     *       {@code JdbcTemplate} bean — pollutes this test's context
     *       with fake DB infrastructure that it does not own.</li>
     *   <li>A real DataSource / H2 / MySQL — the wrong test for
     *       that; the integration test owns it.</li>
     * </ul>
     *
     * <h3>Why not stub the mock in this MICRO</h3>
     *
     * {@code SpikeSpaceAccess.canAccess} is still the hard-coded
     * SPIKE rule (MICRO-06B-A / MICRO-06C-A): it checks the
     * {@code Authentication} principal name and the {@code spaceId}
     * path variable, and it does NOT call
     * {@code SpikeSpaceMembershipRepository.hasActiveMembership}.
     * The next MICRO will wire the repository into
     * {@code SpikeSpaceAccess}; only at that point will tests 7, 8,
     * and 9 need mock stubbing to control what the repository returns.
     *
     * Until that wiring lands, this mock exists purely to keep this
     * test class's Spring context bootable without a database.
     * Adding {@code when(...)} / {@code given(...)} calls today would
     * be dead code and would also imply the repository is called
     * when it is not.
     */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /**
     * BUSINESS-001: mock the production LearningSpace mapper so this
     * Security / HTTP boundary test keeps running without
     * MyBatis-Plus / DataSource under the {@code test} profile.
     * Not stubbed — none of the 10 assertions touch space
     * persistence. The real mapper is exercised by
     * {@code LearningSpaceVerticalSliceIntegrationTest}.
     */
    @MockitoBean
    private LearningSpaceMapper learningSpaceMapper;
    /**
     * BUSINESS-002: mock the production Source mapper so this
     * full-context test keeps running without MyBatis-Plus /
     * DataSource under this profile. Not stubbed — this test never
     * touches Source persistence. The real SourceMapper is exercised
     * by SourceVerticalSliceIntegrationTest (flyway-it profile).
     */
    @MockitoBean
    private SourceMapper sourceMapper;

    /**
     * BUSINESS-003: mock the Knowledge mappers so this
     * full-context test keeps running without MyBatis-Plus /
     * DataSource under this profile. Not stubbed — this test
     * never touches Knowledge persistence.
     */
    @MockitoBean
    private KnowledgeCategoryMapper knowledgeCategoryMapper;

    /**
     * BUSINESS-003: mock the KnowledgePoint mapper (see above).
     */
    @MockitoBean
    private KnowledgePointMapper knowledgePointMapper;

    /** BUSINESS-007: keep the test profile context bootable. */
    @MockitoBean
    private KnowledgePointSourceMapper knowledgePointSourceMapper;

    /** BUSINESS-004: keep the test profile context bootable. */
    @MockitoBean
    private SourceAssetMapper sourceAssetMapper;

    /** BUSINESS-005: keep the test profile context bootable. */
    @MockitoBean
    private IngestionJobMapper ingestionJobMapper;

    /** BUSINESS-006: keep the test profile context bootable. */
    @MockitoBean
    private SourcePageMapper sourcePageMapper;

    /** BUSINESS-006: keep the test profile context bootable. */
    @MockitoBean
    private ContentBlockMapper contentBlockMapper;
    @MockitoBean
    private com.aistudy.server.question.mapper.QuestionMapper questionMapper;
    @MockitoBean
    private com.aistudy.server.question.mapper.QuestionOptionMapper questionOptionMapper;
    @MockitoBean
    private com.aistudy.server.question.mapper.QuestionKnowledgePointMapper questionKnowledgePointMapper;
    @MockitoBean
    private com.aistudy.server.practice.mapper.PracticeSessionMapper practiceSessionMapper;
    @MockitoBean
    private com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper practiceSessionQuestionMapper;
    @MockitoBean
    private com.aistudy.server.practice.mapper.PracticeAnswerMapper practiceAnswerMapper;
    @MockitoBean
    private com.aistudy.server.wrong.mapper.WrongQuestionMapper wrongQuestionMapper;
    @MockitoBean
    private com.aistudy.server.wrong.mapper.ReviewTaskMapper reviewTaskMapper;
    @MockitoBean
    private com.aistudy.server.wrong.mapper.ReviewRecordMapper reviewRecordMapper;
    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamMapper examMapper;
    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamPaperMapper examPaperMapper;
    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamQuestionMapper examQuestionMapper;
    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamAttemptMapper examAttemptMapper;
    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamAnswerMapper examAnswerMapper;
    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamResultMapper examResultMapper;
    @MockitoBean
    private com.aistudy.server.mastery.mapper.MasteryMapper masteryMapper;
    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamDiagnosisMapper examDiagnosisMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamDiagnosisItemMapper examDiagnosisItemMapper;
    @MockitoBean
    private com.aistudy.server.studyplan.mapper.StudyPlanMapper studyPlanMapper;

    @MockitoBean
    private com.aistudy.server.studyplan.mapper.StudyTaskMapper studyTaskMapper;

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountRoleMapper userAccountRoleMapper;

    @MockitoBean
    private com.aistudy.server.auth.mapper.RefreshSessionMapper refreshSessionMapper;

    @MockitoBean
    private com.aistudy.server.search.mapper.SearchMapper searchMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiConversationMapper aiConversationMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageMapper aiMessageMapper;




    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @Test
    void healthRemainsAnonymous() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * SPIKE-005 MICRO-01B: an anonymous GET to {@code /v3/api-docs} — the
     * default springdoc JSON contract endpoint — must return HTTP 200 and
     * must contain at least the {@code openapi} version string and the
     * {@code paths} map.
     *
     * This is the smallest meaningful assertion that "the OpenAPI contract
     * endpoint is reachable anonymously and speaks valid OpenAPI". We do
     * NOT hard-code the full contract body: any future change that adds
     * more endpoints, schemas, or securitySchemes (a later MICRO) will
     * continue to pass this test as long as the JSON remains a valid
     * OpenAPI document at minimum. Asserting on the concrete set of paths
     * today would force this MICRO to own the endpoint enumeration, which
     * it does not.
     *
     * The assertion also proves that MICRO-01B's filter-chain change
     * actually took effect: without the
     * {@code requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()}
     * rule, the current {@code anyRequest().authenticated()} would reject
     * this anonymous call with 401 before springdoc's handler ran.
     */
    @Test
    void anonymousCanAccessOpenApiDocs() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(jsonPath("$.paths").isMap());
    }

    @Test
    void protectedEndpointReturns401ForAnonymousCaller() throws Exception {
        mockMvc.perform(get("/api/v1/spike/protected"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * MICRO-05B-A: a request carrying a valid HS256 JWT as a Bearer token
     * must authenticate against the same {@code anyRequest().authenticated()}
     * rule that returns 401 for anonymous callers. This exercises the full
     * path: {@code SpikeJwtTokenService.issueAccessToken} signs a real token
     * using the shared SecretKey, {@code Authorization: Bearer <token>} is
     * parsed by the Resource Server, the token is decoded and verified via
     * the shared {@link org.springframework.security.oauth2.jwt.JwtDecoder}
     * bean, and the resulting Authentication is placed in the SecurityContext
     * before the controller runs.
     *
     * Deliberately does not inspect the Authentication object itself — the
     * SPIKE only needs to prove the resource is reachable with a valid
     * Bearer token. Role / authority mapping, subject extraction, and
     * claims-based authorization are future MICROs.
     */
    @Test
    void validBearerTokenCanAccessProtectedEndpoint() throws Exception {
        String token = tokenService.issueAccessToken("spike-user-1");

        mockMvc.perform(get("/api/v1/spike/protected")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));
    }

    /**
     * MICRO-05C-A: a request carrying a Bearer token whose JWT signature
     * segment has been modified (while header and payload stay byte-for-byte
     * identical) must return exactly HTTP 401.
     *
     * Why tamper the signature and not the payload?
     *   - Header remains valid → not a malformed JWT.
     *   - Payload remains valid JSON with the same claims → not a claim
     *     parse failure, not an issuer / subject / exp failure.
     *   - Only the signature differs → the Resource Server's
     *     {@code JwtDecoder} sees a JWT whose HMAC-SHA256 over
     *     {@code header.payload} does NOT match the presented signature.
     *
     * Therefore the 401 response can be attributed to exactly one root
     * cause: signature verification failed. This is a stronger guarantee
     * than a payload-tampering test, which could also fail JSON parsing
     * before reaching signature verification.
     *
     * Sanity assertions in the test body verify that the tampered token
     * still has 3 segments, that the header and payload segments are
     * byte-for-byte identical to the original, and that the signature
     * segment is different. Without those checks, a regression in the
     * helper (e.g. accidentally replacing the whole token) could silently
     * still pass the 401 assertion for the wrong reason.
     *
     * Deliberately does NOT test expired tokens, malformed tokens, wrong
     * issuers, wrong algorithms, missing Bearer prefixes, empty Bearer
     * tokens, or role-based authorization — those are future MICROs.
     */
    @Test
    void invalidSignatureBearerTokenReturns401() throws Exception {
        String token = tokenService.issueAccessToken("spike-user-1");
        String[] originalSegments = token.split("\\.", -1);

        // Sanity: original token is a valid 3-segment JWT.
        assertEquals(3, originalSegments.length,
                "original token must have 3 dot-separated segments");

        String tamperedToken = tamperSignature(token);
        String[] tamperedSegments = tamperedToken.split("\\.", -1);

        // Sanity: tampered token is still structurally a JWT (3 segments),
        // header and payload unchanged, signature changed.
        assertEquals(3, tamperedSegments.length,
                "tampered token must still have 3 dot-separated segments");
        assertEquals(originalSegments[0], tamperedSegments[0],
                "header segment must be byte-for-byte identical");
        assertEquals(originalSegments[1], tamperedSegments[1],
                "payload segment must be byte-for-byte identical");
        assertNotEquals(originalSegments[2], tamperedSegments[2],
                "signature segment must differ from original");

        // The actual assertion: Resource Server must return exactly 401
        // (not 200, not 403, not 500).
        mockMvc.perform(get("/api/v1/spike/protected")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Returns a token whose header and payload segments are byte-for-byte
     * identical to the input, but whose signature segment has had its first
     * character replaced with a different Base64URL alphabet character.
     *
     * Because the signature is Base64URL-encoded and both {@code 'A'} and
     * {@code 'B'} are valid Base64URL characters, the modified signature
     * segment remains a syntactically valid Base64URL string. The HMAC-SHA256
     * verification will fail (any single-bit change in a signature invalidates
     * the MAC), which is exactly what we want to trigger.
     *
     * The rule "if first == 'A' then 'B' else 'A'" guarantees that the
     * resulting character is always different from the original first
     * character, so the sanity {@code assertNotEquals} on the signature
     * segment will hold.
     *
     * @throws IllegalStateException if the input token does not have exactly
     *         3 segments or the signature segment is empty — the helper
     *         refuses to produce a "tampered" token from a malformed input
     *         because that would fail the sanity assertions for the wrong
     *         reason.
     */
    private static String tamperSignature(String token) {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            throw new IllegalStateException(
                    "input token must have exactly 3 segments to tamper signature");
        }
        String signature = segments[2];
        if (signature.isEmpty()) {
            throw new IllegalStateException(
                    "signature segment must be non-empty to tamper");
        }
        char first = signature.charAt(0);
        char replacement = (first == 'A') ? 'B' : 'A';
        String modifiedSignature = String.valueOf(replacement)
                + signature.substring(1);
        return segments[0] + "." + segments[1] + "." + modifiedSignature;
    }

    /**
     * MICRO-05D-A: a request carrying a Bearer token whose JWT signature is
     * cryptographically valid but whose {@code exp} claim is already in the
     * past must return exactly HTTP 401.
     *
     * Why sign a fresh token with an old {@code exp} rather than tamper an
     * existing token's payload?
     *   - Header remains valid → not a malformed JWT.
     *   - Payload is valid JSON with the same claims shape → not a claim
     *     parse failure.
     *   - Signature is cryptographically correct (produced by the same
     *     Spring-context {@link JwtEncoder} bean that MICRO-05B-A uses for
     *     valid Bearer tests) → not a signature failure.
     *   - {@code exp} is deliberately in the past → the decoder's only
     *     rejection cause is expiration.
     *
     * Using the shared {@link JwtEncoder} bean (rather than modifying
     * {@link SpikeJwtTokenService} or hand-rolling an encoder) guarantees
     * the signed token is signed with the SAME {@code SecretKey} that
     * {@link SpikeSecurityConfig} uses for the Resource Server's
     * {@link org.springframework.security.oauth2.jwt.JwtDecoder}. Any
     * rejection by the decoder therefore cannot be attributed to a
     * key mismatch — it must be attributed to the past {@code exp}.
     *
     * The chosen timestamps are deliberately conservative to avoid clock
     * skew ambiguity:
     *   - {@code issuedAt} = now − 10 minutes
     *   - {@code expiresAt} = now − 5 minutes
     * Spring Security's default clock skew is at most a few seconds, so a
     * 5-minute-expired token is unambiguously expired under any
     * reasonable skew tolerance. Using just a few seconds of expiration
     * would risk a false-positive pass on a machine whose clock is skewed
     * forward, which would silently turn this test into a non-assertion.
     *
     * Sanity assertions before the HTTP call confirm the token is a
     * well-formed 3-segment JWT and is non-blank. We deliberately do NOT
     * call {@code jwtDecoder.decode(expiredToken)} as a pre-check — the
     * decoder would throw on this token (by design), and we want the
     * failure to surface through the Resource Server path, not bypass it.
     *
     * Deliberately does NOT test wrong issuer, wrong algorithm, malformed
     * JWT, empty Bearer, or role / permission / spaceId authorization —
     * those are future MICROs.
     */
    @Test
    void expiredBearerTokenReturns401() throws Exception {
        Instant now = Instant.now();
        // 10 minutes in the past issued, expired 5 minutes ago. The 5-minute
        // past-expiration window is deliberately larger than any realistic
        // clock skew tolerance so the assertion cannot be flipped by clock
        // drift on a slow machine.
        Instant issuedAt = now.minus(10, ChronoUnit.MINUTES);
        Instant expiresAt = now.minus(5, ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("aistudy-spike")
                .subject("spike-user-1")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256)
                .build();

        String expiredToken = jwtEncoder
                .encode(JwtEncoderParameters.from(headers, claims))
                .getTokenValue();

        // Sanity: token is a well-formed 3-segment JWT.
        assertNotNull(expiredToken, "expiredToken must not be null");
        assertFalse(expiredToken.isBlank(), "expiredToken must not be blank");
        String[] segments = expiredToken.split("\\.", -1);
        assertEquals(3, segments.length,
                "expiredToken must have 3 dot-separated segments");

        // The actual assertion: Resource Server must return exactly 401
        // (not 200, not 403, not 500) for an expired Bearer token.
        mockMvc.perform(get("/api/v1/spike/protected")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    /**
     * MICRO-06A: a request carrying a valid HS256 JWT to a
     * {@code @PreAuthorize("denyAll()")} endpoint must be rejected with
     * exactly HTTP 403 (Forbidden), not 401 (Unauthorized).
     *
     * Why does this prove 401 vs 403 are correctly distinguished?
     *   - The Bearer token is signed with the same SecretKey used by the
     *     Resource Server's JwtDecoder (via the shared JwtEncoder bean
     *     used in MICRO-05B-A's test, here obtained by calling
     *     {@code SpikeJwtTokenService.issueAccessToken} on the same Spring
     *     context). So the JWT decodes and verifies cleanly — authentication
     *     succeeds.
     *   - The target endpoint {@code /api/v1/spike/method-denied} has
     *     {@code @PreAuthorize("denyAll()")} on the handler method, so the
     *     Method Security layer denies it after authentication.
     *   - If Method Security were NOT wired in, the request would return
     *     200 with {@code {"status":"SHOULD_NOT_REACH"}}, which would fail
     *     this assertion.
     *   - If authentication were NOT wired in (e.g. a regression that broke
     *     the Resource Server), the request would return 401, which would
     *     also fail this assertion.
     *   - Only when BOTH authentication succeeds AND authorization denies
     *     does the request yield 403.
     *
     * This is the gate for a future MICRO that will replace
     * {@code denyAll()} with a real Space / membership expression. Once
     * that lands, this exact endpoint path and its 403 contract should
     * stay identical; only the SpEL expression changes.
     *
     * Deliberately does NOT mock an Authentication object and does NOT
     * inspect the response body (the response should have no body at all,
     * since the method is never invoked).
     */
    @Test
    void authenticatedButMethodDeniedReturns403() throws Exception {
        String token = tokenService.issueAccessToken("spike-user-1");

        mockMvc.perform(get("/api/v1/spike/method-denied")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /**
     * MICRO-06B-A (test 7): a request carrying a valid HS256 JWT for
     * {@code spike-user-1} to
     * {@code GET /api/v1/spike/spaces/space-A} must return exactly HTTP 200
     * with a body of {@code {"spaceId":"space-A","status":"AUTHORIZED"}}.
     *
     * This is the allow-side of the Space Authorization gate. The 200
     * proves that:
     *   - the JWT was decoded and verified (authentication succeeded),
     *   - {@code Authentication.getName()} resolved to {@code spike-user-1}
     *     (the Resource Server produced an Authentication whose principal
     *     name comes from the JWT's {@code sub} claim),
     *   - the request-supplied {@code spaceId} was {@code "space-A"},
     *   - {@link SpikeSpaceAccess#canAccess(Authentication, String)}
     *     evaluated to {@code true}, and
     *   - Spring Method Security let the request through to the controller.
     *
     * Deliberately does NOT inspect or modify the JWT, does NOT add a
     * spaceId claim, and does NOT mock an Authentication.
     */
    @Test
    void authorizedSpaceReturns200() throws Exception {
        String token = tokenService.issueAccessToken("spike-user-1");

        // MICRO-07E-A: since SpikeSpaceAccess now delegates to the repository,
        // stub the mock so the (spike-user-1, space-A) pair reports ACTIVE.
        // Without this stub, Mockito's default return for a boolean method
        // is false, which would turn this test into a 403 and make the
        // 200 assertion fail for the wrong reason (missing stub, not
        // authorization bug).
        when(membershipRepository.hasActiveMembership(
                "spike-user-1", "space-A")).thenReturn(true);

        mockMvc.perform(get("/api/v1/spike/spaces/space-A")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.spaceId").value("space-A"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        // MICRO-07E-A: prove the JWT subject AND the path spaceId both
        // reached the repository — no client-supplied claim, no hard-coded
        // literal, no spaceId baked into the JWT.
        verify(membershipRepository)
                .hasActiveMembership("spike-user-1", "space-A");
    }

    /**
     * MICRO-06B-A (test 8): a request carrying a valid HS256 JWT for
     * {@code spike-user-1} to
     * {@code GET /api/v1/spike/spaces/space-B} must return exactly HTTP 403
     * Forbidden.
     *
     * Crucially, the JWT used here is <em>identical</em> to the one used in
     * the previous test (test 7, {@link #authorizedSpaceReturns200}) — the
     * same subject, the same signature, the same validity window. The only
     * difference between the two requests is the URL path's
     * {@code {spaceId}} segment: {@code space-A} vs {@code space-B}.
     *
     * Because the JWT is valid and the identity is the same, this 403
     * cannot be attributed to authentication failure (that would be 401).
     * It must be attributed to authorization failure at the
     * {@code @PreAuthorize} layer: {@link SpikeSpaceAccess#canAccess(Authentication, String)}
     * returned {@code false} for the {@code space-B} combination.
     *
     * This is the deny-side of the Space Authorization gate and is the
     * direct counterpart of test 7. Together, tests 7 and 8 prove that:
     *   - the same successful authentication can yield either 200 or 403,
     *   - the deciding variable is the request-supplied {@code spaceId},
     *   - and the decision is made server-side by a dedicated
     *     {@code @PreAuthorize} SpEL expression, not by client-supplied
     *     JWT claims and not by inline {@code if} branches in the
     *     controller.
     *
     * Deliberately does NOT test:
     *   - a different user (that would fail authentication or produce a
     *     different principal name — orthogonal to the space-denial gate).
     *   - an invalid or expired JWT (that would return 401, not 403 —
     *     already covered by tests 4 and 5).
     *   - a tampered JWT (also 401 — covered by test 4).
     *   - the absence of a Bearer header at all (that would be 401 —
     *     covered by test 2).
     */
    @Test
    void unauthorizedSpaceReturns403() throws Exception {
        String token = tokenService.issueAccessToken("spike-user-1");

        // MICRO-07E-A: stub the mock so the (spike-user-1, space-B) pair
        // reports no ACTIVE membership. Without this stub, the mock's
        // default boolean return is false, which would produce the correct
        // 403 for the wrong reason (missing stub); the explicit stub makes
        // the test's intent — "space-B is not authorized for spike-user-1"
        // — clear and self-documenting.
        when(membershipRepository.hasActiveMembership(
                "spike-user-1", "space-B")).thenReturn(false);

        mockMvc.perform(get("/api/v1/spike/spaces/space-B")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // MICRO-07E-A: prove the decision was made by the repository
        // lookup for the SAME subject (spike-user-1) but a DIFFERENT
        // spaceId (space-B). The path variable space-B was forwarded,
        // not short-circuited by a hard-coded deny.
        verify(membershipRepository)
                .hasActiveMembership("spike-user-1", "space-B");
    }

    /**
     * MICRO-06C-A (test 9): a request carrying a valid HS256 JWT for
     * {@code spike-user-2} to
     * {@code GET /api/v1/spike/spaces/space-A} must return exactly HTTP 403
     * Forbidden.
     *
     * This is the identity-side half of the Space Authorization gate. Tests
     * 7 and 8 already proved that the decision varies with {@code spaceId}
     * for a fixed caller (spike-user-1). This test fixes
     * {@code spaceId = "space-A"} — the space that test 7 shows spike-user-1
     * is allowed to access — and swaps the caller to spike-user-2. The
     * expected 403 proves that the decision varies with the authenticated
     * identity too, i.e. {@link SpikeSpaceAccess#canAccess(Authentication, String)}
     * depends on BOTH inputs, not on the spaceId alone.
     *
     * Why 403 and not 401? The JWT for spike-user-2 is cryptographically
     * valid: same issuer, same HS256 algorithm, same SecretKey, same
     * 5-minute lifetime, unexpired, untampered. The Resource Server
     * decodes and verifies it successfully and produces an
     * {@code Authentication} whose {@code getName()} returns
     * {@code spike-user-2}. That Authentication is authenticated, so the
     * filter chain does not short-circuit with 401. What kills the request
     * is the subsequent {@code @PreAuthorize} SpEL call to
     * {@code SpikeSpaceAccess.canAccess}:
     * <pre>
     *     authentication != null                 // true
     * && authentication.isAuthenticated()        // true
     * && "spike-user-1".equals(authentication.getName())
     *                                     // FALSE (getName == "spike-user-2")
     * && "space-A".equals(spaceId)              // true
     * </pre>
     * The first {@code false} short-circuits the whole expression to
     * {@code false}, which becomes an {@code AccessDeniedException}, which
     * the filter chain maps to HTTP 403.
     *
     * Together with tests 7 and 8, this trio enumerates all three
     * meaningful user/space combinations against the current hard-coded
     * membership rule:
     * <pre>
     *   spike-user-1 + space-A  → 200  (test 7)
     *   spike-user-1 + space-B  → 403  (test 8)  // wrong space, right user
     *   spike-user-2 + space-A  → 403  (test 9)  // right space, wrong user
     * </pre>
     * No future MICRO that adds real User ↔ LearningSpace persistence
     * should be able to regress any of these three results — the same
     * endpoint path, the same {@code @PreAuthorize} SpEL expression, and
     * the same three status contracts must all hold.
     *
     * Deliberately does NOT test:
     *   - {@code spike-user-2 + space-B}: that would also be 403 but for
     *     the same identity-mismatch reason as test 8 already covers —
     *     adding it would duplicate the assertion, not add coverage.
     *   - an anonymous caller to space-A or space-B: anonymous callers hit
     *     the HTTP-level 401 before Method Security runs, already covered
     *     by test 2's pattern.
     *   - a different subject with a tampered signature (test 4 covers
     *     signature tampering with a valid subject).
     *   - spaceIds embedded in the JWT: this SPIKE deliberately does not
     *     put space membership into the token — the server decides.
     */
    @Test
    void differentUserCannotAccessAuthorizedSpace() throws Exception {
        String token = tokenService.issueAccessToken("spike-user-2");

        // MICRO-07E-A: stub the mock so the (spike-user-2, space-A) pair
        // reports no ACTIVE membership. Together with tests 7 and 8,
        // this trio enumerates the space authorization decision across
        // both the identity dimension (spike-user-1 vs spike-user-2)
        // and the space dimension (space-A vs space-B).
        when(membershipRepository.hasActiveMembership(
                "spike-user-2", "space-A")).thenReturn(false);

        mockMvc.perform(get("/api/v1/spike/spaces/space-A")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // MICRO-07E-A: prove the DIFFERENT subject (spike-user-2) reached
        // the repository — the decision is not short-circuited by
        // "space-A is fine" without checking the caller's identity.
        verify(membershipRepository)
                .hasActiveMembership("spike-user-2", "space-A");
    }
}
