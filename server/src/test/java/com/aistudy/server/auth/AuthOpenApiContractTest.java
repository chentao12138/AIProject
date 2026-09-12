package com.aistudy.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-017 — OpenAPI auth contract smoke test.
 *
 * <p>Verifies the live OpenAPI document under the {@code flyway-it}
 * profile: {@code /api/v1/auth/login} must NOT carry a bearer security
 * requirement while {@code /api/v1/auth/me} must, and the auth DTO
 * schemas must be present. Uses existence-level assertions only — no
 * brittle nested {@code $ref}/property expectations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("flyway-it")
class AuthOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginHasNoBearerRequirementAndMeDoes() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                // login: anonymous operation — no security array at all
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                // me: protected operation — bearerAuth required
                .andExpect(jsonPath("$.paths['/api/v1/auth/me']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].bearerAuth").exists())
                // bearerAuth scheme shape (type=http, scheme=bearer, bearerFormat=JWT)
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                // auth DTO schemas exist (login/refresh return TokenPairResponse)
                .andExpect(jsonPath("$.components.schemas.LoginRequest").exists())
                .andExpect(jsonPath("$.components.schemas.TokenPairResponse").exists())
                .andExpect(jsonPath("$.components.schemas.CurrentUserResponse").exists());
    }
}
