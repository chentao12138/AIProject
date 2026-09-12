package com.aistudy.server.operations;

import com.aistudy.server.auth.security.AuthJwtEncoder;
import com.aistudy.server.auth.security.AuthProperties;
import com.aistudy.server.spike.auth.SpikeJwtTokenService;
import com.aistudy.server.testsupport.TestProfileMapperMocks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-025 — actuator security contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestProfileMapperMocks.class)
class ActuatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("authJwtDecoder")
    private JwtDecoder authJwtDecoder;

    @Autowired
    private AuthJwtEncoder authJwtEncoder;

    @Autowired
    private AuthProperties authProperties;

    @MockitoBean
    private SpikeJwtTokenService spikeJwtTokenService;


    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

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

    @Test
    void healthEndpointsAreAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void protectedActuatorRequiresAuthAndAcceptsAdmin() throws Exception {
        String token = issueProductionToken("actuator-user", List.of("USER"));
        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", token))
                .andExpect(status().isForbidden());

        token = issueProductionToken("actuator-admin", List.of("USER", "ADMIN"));
        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void spikeJwtIsRejectedByProtectedActuator() throws Exception {
        String spikeToken = new SpikeJwtTokenService(
                new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                        new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                                javax.crypto.KeyGenerator.getInstance("HmacSHA256").generateKey()
                        )
                )
        ).issueAccessToken("spike-user");

        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", "Bearer " + spikeToken))
                .andExpect(status().isUnauthorized());
    }

    private String issueProductionToken(String subject, List<String> roles) {
        Instant now = Instant.now();
        String token = authJwtEncoder.encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                        org.springframework.security.oauth2.jwt.JwsHeader.with(
                                org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256
                        ).build(),
                        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                                .issuer("aistudy")
                                .subject(subject)
                                .issuedAt(now)
                                .expiresAt(now.plusSeconds(authProperties.getJwt().getAccessTokenTtlSeconds()))
                                .claim("tokenType", "access")
                                .claim("roles", new java.util.ArrayList<>(roles))
                                .build()
                )
        ).getTokenValue();
        return "Bearer " + token;
    }
}
