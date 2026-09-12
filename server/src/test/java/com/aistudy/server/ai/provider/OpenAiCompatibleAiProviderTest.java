package com.aistudy.server.ai.provider;

import com.aistudy.server.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI-001 — OpenAI-compatible adapter against a local deterministic stub.
 * No external network.
 */
class OpenAiCompatibleAiProviderTest {

    private HttpServer server;
    private String baseUrl;
    private AiProperties properties;
    private OpenAiCompatibleAiProvider provider;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = """
            {"choices":[{"message":{"role":"assistant","content":"你好"}}],\
            "model":"stub-model","usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
            """;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("test-api-key");
        properties.setModel("stub-model");
        properties.setTemperature(0.2);
        properties.setMaxOutputTokens(128);
        provider = new OpenAiCompatibleAiProvider(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void successParsesContentModelAndUsage() {
        AiChatResponse response = provider.chat(new AiChatRequest(
                List.of(AiChatMessage.system("sys"), AiChatMessage.user("hi")),
                null, null));
        assertEquals("你好", response.content());
        assertEquals("stub-model", response.model());
        assertNotNull(response.usage());
        assertEquals(3, response.usage().promptTokens());
        assertEquals(5, response.usage().completionTokens());
        assertEquals(8, response.usage().totalTokens());
    }

    @Test
    void requestUsesChatCompletionsPathAndBearerAuth() {
        provider.chat(new AiChatRequest(
                List.of(AiChatMessage.system("sys"), AiChatMessage.user("x")),
                null, null));
        assertEquals("/chat/completions", lastPath.get());
        assertEquals("Bearer test-api-key", lastAuth.get());
        String body = lastBody.get();
        assertTrue(body.contains("\"model\":\"stub-model\""));
        assertTrue(body.contains("\"max_tokens\":128"));
        assertTrue(body.contains("temperature"));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"role\":\"user\""));
    }

    @Test
    void trailingSlashBaseUrlDoesNotDoublePath() {
        properties.setBaseUrl(baseUrl + "/");
        OpenAiCompatibleAiProvider withSlash =
                new OpenAiCompatibleAiProvider(properties, new ObjectMapper());
        withSlash.chat(new AiChatRequest(List.of(AiChatMessage.user("x")), null, null));
        assertEquals("/chat/completions", lastPath.get());
    }

    @Test
    void missingUsageLeavesNullFields() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}],"model":"stub-model"}
                """;
        AiChatResponse response = provider.chat(new AiChatRequest(
                List.of(AiChatMessage.user("x")), null, null));
        assertEquals("ok", response.content());
        assertNotNull(response.usage());
        assertEquals(null, response.usage().promptTokens());
        assertEquals(null, response.usage().completionTokens());
        assertEquals(null, response.usage().totalTokens());
    }

    @Test
    void unconfiguredThrowsAiNotConfiguredAndDoesNotLeakKey() {
        properties.setEnabled(false);
        properties.setApiKey("secret-should-not-leak");
        OpenAiCompatibleAiProvider disabled =
                new OpenAiCompatibleAiProvider(properties, new ObjectMapper());
        AiProviderException ex = assertThrows(AiProviderException.class,
                () -> disabled.chat(new AiChatRequest(List.of(AiChatMessage.user("x")), null, null)));
        assertEquals(AiErrorCode.AI_NOT_CONFIGURED, ex.errorCode());
        assertFalse(ex.getMessage().contains("secret-should-not-leak"));
    }

    @Test
    void rejectedStatusesMapToRejected() {
        for (int status : new int[]{401, 403, 429, 400}) {
            responseStatus = status;
            responseBody = "{\"error\":\"nope\"}";
            AiProviderException ex = assertThrows(AiProviderException.class,
                    () -> provider.chat(new AiChatRequest(List.of(AiChatMessage.user("x")), null, null)));
            assertEquals(AiErrorCode.AI_PROVIDER_REJECTED, ex.errorCode(), "status=" + status);
            assertFalse(ex.getMessage().contains("test-api-key"));
            assertFalse(ex.getMessage().contains("nope"));
        }
    }

    @Test
    void serverErrorMapsToUnavailable() {
        responseStatus = 500;
        responseBody = "{\"error\":\"boom-secret-upstream\"}";
        AiProviderException ex = assertThrows(AiProviderException.class,
                () -> provider.chat(new AiChatRequest(List.of(AiChatMessage.user("x")), null, null)));
        assertEquals(AiErrorCode.AI_PROVIDER_UNAVAILABLE, ex.errorCode());
        assertFalse(ex.getMessage().contains("boom-secret-upstream"));
        assertFalse(ex.getMessage().contains("test-api-key"));
    }

    @Test
    void malformedJsonMapsToResponseInvalid() {
        responseBody = "not-json";
        AiProviderException ex = assertThrows(AiProviderException.class,
                () -> provider.chat(new AiChatRequest(List.of(AiChatMessage.user("x")), null, null)));
        assertEquals(AiErrorCode.AI_PROVIDER_RESPONSE_INVALID, ex.errorCode());
        assertFalse(ex.getMessage().contains("not-json"));
    }

    @Test
    void emptyChoicesMapToResponseInvalid() {
        responseBody = "{\"choices\":[]}";
        AiProviderException ex = assertThrows(AiProviderException.class,
                () -> provider.chat(new AiChatRequest(List.of(AiChatMessage.user("x")), null, null)));
        assertEquals(AiErrorCode.AI_PROVIDER_RESPONSE_INVALID, ex.errorCode());
    }

    @Test
    void connectionRefusedMapsToUnavailable() throws IOException {
        server.stop(0);
        server = null;
        AiProviderException ex = assertThrows(AiProviderException.class,
                () -> provider.chat(new AiChatRequest(List.of(AiChatMessage.user("x")), null, null)));
        assertEquals(AiErrorCode.AI_PROVIDER_UNAVAILABLE, ex.errorCode());
    }
}
