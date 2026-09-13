package com.aistudy.server.auth;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.entity.UserAccountRole;
import com.aistudy.server.auth.mapper.RefreshSessionMapper;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.mapper.UserAccountRoleMapper;
import com.aistudy.server.auth.security.AuthJwtEncoder;
import com.aistudy.server.auth.security.AuthProperties;
import com.aistudy.server.auth.service.AdminUserService;
import com.aistudy.server.auth.service.AuthenticationService;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.auth.service.RefreshTokenService;
import com.aistudy.server.auth.service.UserAccountService;
import com.aistudy.server.auth.service.UserRoleService;
import com.aistudy.server.auth.service.LastAdminProtectionException;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-019 — RBAC/admin mutations, refresh revocation, and current-JWT authority semantics.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
class AdminSecurityIntegrationTest {

    private static final String RAW_PASSWORD = "admin-P***word";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @Autowired
    private AuthJwtEncoder authJwtEncoder;

    @Autowired
    private AdminUserService adminUserService;

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

    @BeforeEach
    void migrateSchemaAndCleanData() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!"aistudy_flyway_test".equals(database)) {
            throw new IllegalStateException(
                    "Refusing to run AdminSecurityIntegrationTest against '"
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

    private String bearer(String subject, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("aistudy")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(authProperties.getJwt().getAccessTokenTtlSeconds()))
                .claim("tokenType", "access")
                .claim("roles", roles)
                .build();
        String token = authJwtEncoder.encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                        org.springframework.security.oauth2.jwt.JwsHeader.with(
                                org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256
                        ).build(),
                        claims
                )
        ).getTokenValue();
        return "Bearer " + token;
    }

    private String userToken(String subject) {
        return bearer(subject, List.of("USER"));
    }

    private String adminToken(String subject) {
        return bearer(subject, List.of("USER", "ADMIN"));
    }

    @Test
    void userForbiddenFromAdminApi() throws Exception {
        String subject = register("admin-user-forbidden");
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", userToken(subject)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminAcceptedAndAccountCreationReceivesUserRole() throws Exception {
        String subject = register("admin-viewer");

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", adminToken(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value("admin-viewer"))
                .andExpect(jsonPath("$.items[0].roles", hasSize(1)))
                .andExpect(jsonPath("$.items[0].roles[0]").value("USER"));
    }

    @Test
    void roleAndStatusMutationsRevokeRefreshSessions() throws Exception {
        String subject = register("admin-mutation");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"username\":\"admin-mutation\",\"password\":\""
                                + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refreshToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.refreshToken");

        mockMvc.perform(patch("/api/v1/admin/users/" + subject + "/status")
                        .header("Authorization", adminToken(subject))
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lastActiveAdminCannotLoseAdminRole() throws Exception {
        String subject = register("last-admin");
        UserAccount account = userAccountMapper.selectBySubject(subject);
        userAccountRoleMapper.insert(new UserAccountRole() {{
            setUserAccountId(account.getId());
            setRole("ADMIN");
            setCreatedAt(java.time.LocalDateTime.now());
        }});

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", adminToken(subject)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/users/" + subject + "/roles")
                        .header("Authorization", adminToken(subject))
                        .contentType(APPLICATION_JSON_VALUE)
                        .content("{\"roles\":[\"USER\"]}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", adminToken(subject)))
                .andExpect(status().isOk());
    }

    @Test
    void authMeReflectsCurrentJwtAuthorities() throws Exception {
        String subject = register("me-roles");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", userToken(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject))
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        userAccountRoleMapper.insert(new UserAccountRole() {{
            setUserAccountId(userAccountMapper.selectByUsername("me-roles").getId());
            setRole("ADMIN");
            setCreatedAt(java.time.LocalDateTime.now());
        }});

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", userToken(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @AfterEach
    void cleanData() {
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
