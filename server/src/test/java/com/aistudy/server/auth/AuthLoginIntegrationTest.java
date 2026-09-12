package com.aistudy.server.auth;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.RefreshSessionMapper;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.mapper.UserAccountRoleMapper;
import com.aistudy.server.auth.security.AuthProperties;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.auth.service.UserAccountService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
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
 * BUSINESS-017 — login + /auth/me + cross-trust JWT contract.
 *
 * <p>Uses the shared MySQL-backed flyway-it profile so the new
 * user_account table is exercised against the same migration path
 * as BUSINESS-001~016.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
class AuthLoginIntegrationTest {

    private static final String RAW_PASSWORD = "secret-P***word";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserAccountRoleMapper userAccountRoleMapper;

    @Autowired
    private RefreshSessionMapper refreshSessionMapper;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * flyway-it intentionally has {@code spring.flyway.enabled=false}; this
     * test self-migrates exactly like the other flyway-it integration tests
     * (schema guard → idempotent Flyway migrate → scoped cleanup). Never
     * depends on test execution order or on FlywayMigrationIntegrationTest.
     */
    @BeforeEach
    void migrateSchemaAndCleanUserAccount() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!"aistudy_flyway_test".equals(database)) {
            throw new IllegalStateException(
                    "Refusing to run AuthLoginIntegrationTest against '" + database
                            + "' — expected 'aistudy_flyway_test'");
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

    private String register(String username, String status) {
        String subject = register(username);
        if (!"ACTIVE".equals(status)) {
            UserAccount account = userAccountMapper.selectBySubject(subject);
            if (account != null) {
                account.setStatus(status);
                account.setUpdatedAt(java.time.LocalDateTime.now());
                userAccountMapper.updateById(account);
            }
        }
        return subject;
    }

    private String bearer(String subject) {
        return "Bearer " + jwtAccessTokenService.issueAccessToken(subject);
    }

    @Test
    void loginReturnsTokenForValidCredentials() throws Exception {
        String subject = register("login-ok");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"login-ok\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        register("wrong-password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"wrong-password\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUsernameReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"unknown\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAccountReturns401() throws Exception {
        register("disabled-user", "DISABLED");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"disabled-user\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authMeReturnsSubjectForValidToken() throws Exception {
        String subject = register("me-user");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"me-user\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String accessToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.accessToken");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject))
                .andExpect(jsonPath("$.username").value("me-user"));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        String subject = register("expired-user");
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("aistudy")
                .subject(subject)
                .issueTime(Date.from(now.minusSeconds(300)))
                .expirationTime(Date.from(now.minusSeconds(60)))
                .claim("tokenType", "access")
                .build();
        // Real expired token: signed with the production secret/algorithm
        // (HS256), iat = 300s in the past, exp = 60s in the past. The 401
        // must come from expiry validation, not from signature mismatch.
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(
                Base64.getDecoder().decode(authProperties.getJwt().getSecret().trim())));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + signedJwt.serialize()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void spikeJwtRejectedByProductionProtectedApi() throws Exception {
        String subject = register("spike-reject");
        String spikeToken = new com.aistudy.server.spike.auth.SpikeJwtTokenService(
                new NimbusJwtEncoder(
                        new ImmutableSecret<>(
                                KeyGenerator.getInstance("HmacSHA256").generateKey()
                        )
                )
        ).issueAccessToken(subject);

        mockMvc.perform(get("/api/v1/spaces")
                        .header("Authorization", "Bearer " + spikeToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void productionJwtRejectedBySpikeProtectedApi() throws Exception {
        String subject = register("spike-reject-prod");
        String token = jwtAccessTokenService.issueAccessToken(subject);

        mockMvc.perform(get("/api/v1/spike/protected")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousProductionBusinessApiReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/spaces"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordHashNeverExposedInLoginResponse() throws Exception {
        String subject = register("hash-leak");
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"hash-leak\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UserAccount account = userAccountMapper.selectBySubject(subject);
        String hash = account.getPasswordHash();
        if (body.contains(hash)) {
            throw new AssertionError("password_hash must not be leaked in login response");
        }
    }

    @AfterEach
    void cleanUserAccount() {
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
