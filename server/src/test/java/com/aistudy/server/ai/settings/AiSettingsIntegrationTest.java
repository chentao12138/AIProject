package com.aistudy.server.ai.settings;

import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.testsupport.OwnedSpaceReset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI-009 — per-user BYOK settings HTTP contract (real DB).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
@ResourceLock("aistudy-flyway-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiSettingsIntegrationTest {

    private static final String EXPECTED_SCHEMA = "aistudy_flyway_test";
    private static final String USER_A = "biz-ai-set-a";
    private static final String USER_B = "biz-ai-set-b";
    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @DynamicPropertySource
    static void aiProps(DynamicPropertyRegistry registry) {
        registry.add("aistudy.ai.enabled", () -> "false");
        registry.add("aistudy.ai.secret-key", () -> MASTER_KEY);
    }

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void prepare() {
        assertSchema();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .load().migrate();
        clean();
        tokenA = jwtAccessTokenService.issueAccessToken(USER_A);
        tokenB = jwtAccessTokenService.issueAccessToken(USER_B);
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    @Test
    void getNeverReturnsApiKeyAndIsPerUser() throws Exception {
        mockMvc.perform(put("/api/v1/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"provider\":\"OPENAI_COMPATIBLE\",\"preset\":\"STEPFUN\",\"apiKey\":\"user-a-key-123\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preset").value("STEPFUN"))
                .andExpect(jsonPath("$.baseUrl").value("https://api.stepfun.com/v1"))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        MvcResult a = mockMvc.perform(get("/api/v1/settings/ai")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andReturn();
        assertFalse(a.getResponse().getContentAsString().contains("user-a-key-123"));

        mockMvc.perform(get("/api/v1/settings/ai")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(false));
    }

    @Test
    void putWithoutKeyPreservesAndWithKeyReplaces() throws Exception {
        mockMvc.perform(put("/api/v1/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"baseUrl\":\"https://api.stepfun.com/v1\",\"model\":\"m1\",\"apiKey\":\"first-key\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        String cipher1 = jdbc.queryForObject(
                "SELECT ciphertext FROM ai_provider_secret WHERE user_subject=?", String.class, USER_A);

        mockMvc.perform(put("/api/v1/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"baseUrl\":\"https://api.stepfun.com/v1\",\"model\":\"m2\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(true));
        String cipher2 = jdbc.queryForObject(
                "SELECT ciphertext FROM ai_provider_secret WHERE user_subject=?", String.class, USER_A);
        assertEquals(cipher1, cipher2, "PUT without apiKey must keep secret");

        mockMvc.perform(put("/api/v1/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"baseUrl\":\"https://api.stepfun.com/v1\",\"model\":\"m2\",\"apiKey\":\"second-key\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        String cipher3 = jdbc.queryForObject(
                "SELECT ciphertext FROM ai_provider_secret WHERE user_subject=?", String.class, USER_A);
        assertFalse(cipher1.equals(cipher3), "new key must replace ciphertext");
    }

    @Test
    void placeholderKeyRejected() throws Exception {
        mockMvc.perform(put("/api/v1/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"apiKey\":\"********\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteOnlyRemovesOwnKey() throws Exception {
        mockMvc.perform(put("/api/v1/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"apiKey\":\"a-key\"}")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"apiKey\":\"b-key\"}")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/settings/ai/api-key")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/settings/ai")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.apiKeyConfigured").value(false));
        mockMvc.perform(get("/api/v1/settings/ai")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true));
    }

    @Test
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/api/v1/settings/ai"))
                .andExpect(status().isUnauthorized());
    }

    private void clean() {
        jdbc.update("DELETE FROM ai_provider_secret WHERE user_subject IN (?,?)", USER_A, USER_B);
        jdbc.update("DELETE FROM ai_provider_settings WHERE user_subject IN (?,?)", USER_A, USER_B);
        OwnedSpaceReset.forSubjects(jdbc, USER_A, USER_B);
    }

    private void assertSchema() {
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!EXPECTED_SCHEMA.equals(actual)) {
            throw new IllegalStateException("expected " + EXPECTED_SCHEMA + " got " + actual);
        }
    }
}
