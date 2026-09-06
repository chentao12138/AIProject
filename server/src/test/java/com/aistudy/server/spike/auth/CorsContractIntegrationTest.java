package com.aistudy.server.spike.auth;

import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.space.mapper.LearningSpaceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ELECTRON-CORS-001-B — CORS contract tests.
 *
 * <p>Explicit policy (ServerCorsConfig): allowed origins exactly
 * {@code http://localhost:5173} (dev renderer) and {@code app://aistudy}
 * (production Electron custom origin, verified by the ELECTRON-CORS-001-A
 * runtime probe). Preflight must be answered by the CORS layer before
 * authentication; real GET/POST must still return 401 for anonymous
 * callers WITH the correct Access-Control-Allow-Origin so the browser
 * can read the 401 instead of collapsing it into an opaque CORS network
 * error.
 *
 * <p>Disallowed origins (evil / {@code Origin:null} / wrong localhost
 * port) must never receive an ACAO header. The status of those requests
 * is intentionally NOT pinned — the contract is that the browser cannot
 * obtain a usable ACAO.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsContractIntegrationTest {

    private static final String DEV_ORIGIN = "http://localhost:5173";
    private static final String PROD_ORIGIN = "app://aistudy";
    private static final String PREFLIGHT_METHOD = "GET";
    private static final String PREFLIGHT_HEADERS = "authorization,content-type";

    /** Keep the full-context test profile bootable without a database. */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;
    @MockitoBean
    private LearningSpaceMapper learningSpaceMapper;
    @MockitoBean
    private SourceMapper sourceMapper;
    @MockitoBean
    private KnowledgeCategoryMapper knowledgeCategoryMapper;
    @MockitoBean
    private KnowledgePointMapper knowledgePointMapper;
    @MockitoBean
    private KnowledgePointSourceMapper knowledgePointSourceMapper;
    @MockitoBean
    private SourceAssetMapper sourceAssetMapper;
    @MockitoBean
    private IngestionJobMapper ingestionJobMapper;
    @MockitoBean
    private SourcePageMapper sourcePageMapper;
    @MockitoBean
    private ContentBlockMapper contentBlockMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpikeJwtTokenService tokenService;

    // ---------- PRE-FLIGHT ----------

    /** A. Allowed dev origin preflight -> 2xx + echo + methods/headers. */
    @Test
    void preflightDevOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/spaces")
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, PREFLIGHT_METHOD)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, PREFLIGHT_HEADERS))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEV_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("Authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("Content-Type")));
    }

    /** B. Production Electron origin preflight -> 2xx + echo. */
    @Test
    void preflightAppOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/spaces")
                        .header(HttpHeaders.ORIGIN, PROD_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, PREFLIGHT_METHOD)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, PREFLIGHT_HEADERS))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PROD_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")));
    }

    /** C. Disallowed evil origin -> no ACAO (browser cannot proceed). */
    @Test
    void preflightEvilOriginGetsNoAcao() throws Exception {
        mockMvc.perform(options("/api/v1/spaces")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, PREFLIGHT_METHOD))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /** D. Origin:null must never be allowed. */
    @Test
    void preflightNullOriginGetsNoAcao() throws Exception {
        mockMvc.perform(options("/api/v1/spaces")
                        .header(HttpHeaders.ORIGIN, "null")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, PREFLIGHT_METHOD))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /** E. Wrong localhost port must not be allowed. */
    @Test
    void preflightWrongLocalhostPortGetsNoAcao() throws Exception {
        mockMvc.perform(options("/api/v1/spaces")
                        .header(HttpHeaders.ORIGIN, "http://localhost:9999")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, PREFLIGHT_METHOD))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // ---------- ACTUAL REQUESTS ----------

    /** F. Anonymous GET from app://aistudy -> 401 WITH readable ACAO. */
    @Test
    void anonymousActualGetFromAppOriginReturns401WithAcao() throws Exception {
        mockMvc.perform(get("/api/v1/spaces")
                        .header(HttpHeaders.ORIGIN, PROD_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PROD_ORIGIN));
    }

    /** G. Anonymous GET from dev origin -> 401 WITH readable ACAO. */
    @Test
    void anonymousActualGetFromDevOriginReturns401WithAcao() throws Exception {
        mockMvc.perform(get("/api/v1/spaces")
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEV_ORIGIN));
    }

    /**
     * H. Disallowed origin actual GET -> rejected by the CORS layer
     * (Spring CorsFilter returns 403 "Invalid CORS request" before
     * authentication) and NEVER carries an ACAO header. Status is the
     * framework's deterministic behavior; the contract is that the
     * browser cannot obtain a usable ACAO.
     */
    @Test
    void anonymousActualGetFromEvilOriginIsRejectedWithoutAcao() throws Exception {
        mockMvc.perform(get("/api/v1/spaces")
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /** I. Valid Bearer still authenticates with CORS headers attached. */
    @Test
    void validBearerWithAllowedOriginStillAuthenticates() throws Exception {
        mockMvc.perform(get("/api/v1/spike/protected")
                        .header(HttpHeaders.ORIGIN, PROD_ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + tokenService.issueAccessToken("spike-user-1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PROD_ORIGIN));
    }
}
