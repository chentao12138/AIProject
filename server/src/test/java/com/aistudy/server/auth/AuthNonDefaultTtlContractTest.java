package com.aistudy.server.auth;

import com.aistudy.server.auth.mapper.RefreshSessionMapper;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.mapper.UserAccountRoleMapper;
import com.aistudy.server.auth.service.UserAccountService;
import com.jayway.jsonpath.JsonPath;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-017 — non-default access-token TTL contract.
 *
 * <p>Runs with {@code app.auth.jwt.access-token-ttl-seconds=1200} — a
 * deliberately non-default value — to prove that the login response
 * {@code expiresIn} and the issued JWT's {@code exp - iat} both read the
 * SAME {@link com.aistudy.server.auth.security.AuthProperties} source of
 * truth. {@code JwtAccessTokenService} and {@code LoginResponse.expiresIn}
 * must never drift apart.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@TestPropertySource(properties = "app.auth.jwt.access-token-ttl-seconds=1200")
@ResourceLock("aistudy-flyway-test")
class AuthNonDefaultTtlContractTest {

    private static final long NON_DEFAULT_TTL_SECONDS = 1200;
    private static final String RAW_PASSWORD = "secret-P***word";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private UserAccountRoleMapper userAccountRoleMapper;

    @Autowired
    private RefreshSessionMapper refreshSessionMapper;

    @Autowired
    @Qualifier("authJwtDecoder")
    private JwtDecoder authJwtDecoder;

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
                    "Refusing to run AuthNonDefaultTtlContractTest against '" + database
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

    @Test
    void loginExpiresInAndTokenTtlShareAuthPropertiesSource() throws Exception {
        String subject = userAccountService.createAccount("ttl-user", RAW_PASSWORD);

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"ttl-user\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(NON_DEFAULT_TTL_SECONDS))
                .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(body, "$.accessToken");

        // Verify the EXACT login token with the production decoder: the
        // signature must validate AND exp - iat must equal the configured
        // non-default TTL — the same AuthProperties value LoginResponse
        // exposes as expiresIn.
        Jwt jwt = authJwtDecoder.decode(accessToken);
        assertEquals(NON_DEFAULT_TTL_SECONDS,
                Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds());

        // The same token is accepted by the protected endpoint.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject));
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
