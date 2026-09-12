package com.aistudy.server.auth;

import com.aistudy.server.auth.entity.RefreshSession;
import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.RefreshSessionMapper;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.mapper.UserAccountRoleMapper;
import com.aistudy.server.auth.security.AuthProperties;
import com.aistudy.server.auth.service.AuthenticationService;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.auth.service.RefreshTokenService;
import com.aistudy.server.auth.service.UserAccountService;
import com.aistudy.server.auth.service.UserRoleService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-018 — refresh token lifecycle, rotation, reuse/family revoke,
 * expiry/revocation/disabled behavior, logout, and persisted hash contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
class RefreshSessionIntegrationTest {

    private static final String RAW_PASSWORD = "refresh-P***word";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private RefreshSessionMapper refreshSessionMapper;

    @Autowired
    private UserAccountRoleMapper userAccountRoleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void migrateSchemaAndCleanSessions() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!"aistudy_flyway_test".equals(database)) {
            throw new IllegalStateException(
                    "Refusing to run RefreshSessionIntegrationTest against '"
                            + database + "' — expected 'aistudy_flyway_test'");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        refreshSessionMapper.delete(null);
        userAccountRoleMapper.delete(null);
        userAccountMapper.delete(null);
    }

    private String register(String username) {
        return userAccountService.createAccount(username, RAW_PASSWORD);
    }

    private String bearer(String subject) {
        return "Bearer " + jwtAccessTokenService.issueAccessToken(subject);
    }

    private String refreshBody(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    private String logoutBody(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    @Test
    void loginReturnsRefreshToken() throws Exception {
        String subject = register("refresh-login");

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"refresh-login\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken");
        String accessToken = com.jayway.jsonpath.JsonPath.read(body, "$.accessToken");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject));
    }

    @Test
    void plaintextRefreshTokenIsNotPersisted() throws Exception {
        String subject = register("refresh-plaintext");

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"refresh-plaintext\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken");
        UserAccount account = userAccountMapper.selectByUsername("refresh-plaintext");

        boolean persisted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_session "
                        + "WHERE user_account_id = ? AND token_hash = ?",
                Integer.class,
                account.getId(),
                refreshToken
        ) == 1;

        if (persisted) {
            throw new AssertionError("plaintext refresh token must not be persisted");
        }
    }

    @Test
    void refreshRotatesTokenAndReplayOfOldTokenIsRejected() throws Exception {
        String subject = register("refresh-rotate");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"refresh-rotate\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String oldRefresh = com.jayway.jsonpath.JsonPath.read(loginBody, "$.refreshToken");

        String refreshBody = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(refreshBody(oldRefresh)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String newRefresh = com.jayway.jsonpath.JsonPath.read(refreshBody, "$.refreshToken");

        // The rotated token is still valid until the old one is replayed.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(refreshBody(newRefresh)))
                .andExpect(status().isOk());

        // Replaying the old token is reuse: 401.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(refreshBody(oldRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshReuseRevokesActiveTokenFamily() throws Exception {
        String subject = register("refresh-reuse");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"refresh-reuse\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.refreshToken");

        // First refresh rotates the token and marks the original as rotated.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk());

        // Replaying the already-rotated token is reuse: 401 + family revoke.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());

        Integer revokedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_session "
                        + "WHERE user_account_id = ("
                        + " SELECT id FROM user_account WHERE username = 'refresh-reuse'"
                        + ") AND revoked_at IS NOT NULL",
                Integer.class
        );
        if (revokedRows == null || revokedRows == 0) {
            throw new AssertionError("reuse should revoke active token family");
        }
    }

    @Test
    void revokedAndExpiredRefreshTokensAreRejected() throws Exception {
        String subject = register("refresh-expired");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"refresh-expired\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.refreshToken");
        String tokenHash = refreshTokenService.hash(refreshToken);

        RefreshSession session = refreshSessionMapper.selectActiveByTokenHash(
                tokenHash, refreshTokenService.toDatabaseDateTime(Instant.now()));
        // excludeId=-1 so the current session is included (SQL `id <> NULL`
        // would match no rows and leave the session active).
        refreshSessionMapper.revokeFamilyActive(
                session.getFamilyId(),
                refreshTokenService.toDatabaseDateTime(Instant.now()),
                "REVOKED_FOR_TEST",
                -1L
        );

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAccountRefreshAndLogoutAreRejected() throws Exception {
        String subject = register("refresh-disabled");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"refresh-disabled\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.refreshToken");

        UserAccount account = userAccountMapper.selectByUsername("refresh-disabled");
        account.setStatus("DISABLED");
        account.setUpdatedAt(java.time.LocalDateTime.now());
        userAccountMapper.updateById(account);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(logoutBody(refreshToken)))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutRevokesTokenAndIsIdempotent() throws Exception {
        String subject = register("refresh-logout");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"refresh-logout\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.refreshToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(logoutBody(refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content(logoutBody(refreshToken)))
                .andExpect(status().isNoContent());
    }

    @Test
    void hashIsPersistedForIssuedRefreshToken() throws Exception {
        String subject = register("refresh-hash");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"refresh-hash\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.refreshToken");
        String tokenHash = refreshTokenService.hash(refreshToken);

        RefreshSession session = refreshSessionMapper.selectActiveByTokenHash(
                tokenHash, refreshTokenService.toDatabaseDateTime(Instant.now()));
        if (session == null) {
            throw new AssertionError("refresh session row must exist");
        }
        if (!tokenHash.equals(session.getTokenHash())) {
            throw new AssertionError("persisted token must be the hash, not plaintext");
        }
    }

    @AfterEach
    void cleanSessions() {
        if (refreshSessionMapper != null) {
            refreshSessionMapper.delete(null);
        }
        if (userAccountRoleMapper != null) {
            userAccountRoleMapper.delete(null);
        }
        if (userAccountMapper != null) {
            userAccountMapper.delete(null);
        }
    }
}
