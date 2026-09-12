package com.aistudy.server.ai.provider;

import com.aistudy.server.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI-001 — OpenAI-compatible Chat Completions adapter.
 *
 * <p>Uses JDK {@link HttpClient} (already on the classpath) so no vendor
 * SDK is required. Vendor DTOs never leave this class.
 */
@Component
public class OpenAiCompatibleAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAiProvider.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleAiProvider(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        if (!properties.isConfigured()) {
            throw new AiProviderException(AiErrorCode.AI_NOT_CONFIGURED,
                    "AI provider is not configured");
        }
        String endpoint = joinUrl(properties.getBaseUrl(), "/chat/completions");
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(request)))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new AiProviderException(AiErrorCode.AI_NOT_CONFIGURED,
                    "AI base-url is invalid", e);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_TIMEOUT,
                    "AI provider timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "AI provider call interrupted", e);
        } catch (Exception e) {
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "AI provider is unavailable", e);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403 || status == 429 || (status >= 400 && status < 500)) {
            log.warn("AI provider rejected request status={}", status);
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_REJECTED,
                    "AI provider rejected the request");
        }
        if (status < 200 || status >= 300) {
            log.warn("AI provider unavailable status={}", status);
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "AI provider is unavailable");
        }
        return parseResponse(response.body());
    }

    private String buildBody(AiChatRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getModel());
        root.put("temperature", request.temperature() != null
                ? request.temperature() : properties.getTemperature());
        int maxTokens = request.maxOutputTokens() != null
                ? request.maxOutputTokens() : properties.getMaxOutputTokens();
        root.put("max_tokens", maxTokens);
        ArrayNode messages = root.putArray("messages");
        List<AiChatMessage> safe = request.messages() == null ? List.of() : request.messages();
        for (AiChatMessage message : safe) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role().name().toLowerCase());
            node.put("content", message.content() == null ? "" : message.content());
        }
        return root.toString();
    }

    private AiChatResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiProviderException(AiErrorCode.AI_PROVIDER_RESPONSE_INVALID,
                        "AI provider returned an empty response");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content == null) {
                content = "";
            }
            JsonNode usageNode = root.path("usage");
            AiUsage usage = AiUsage.empty();
            if (usageNode.isObject()) {
                Integer prompt = usageNode.has("prompt_tokens")
                        && !usageNode.get("prompt_tokens").isNull()
                        ? usageNode.get("prompt_tokens").asInt() : null;
                Integer completion = usageNode.has("completion_tokens")
                        && !usageNode.get("completion_tokens").isNull()
                        ? usageNode.get("completion_tokens").asInt() : null;
                Integer total = usageNode.has("total_tokens")
                        && !usageNode.get("total_tokens").isNull()
                        ? usageNode.get("total_tokens").asInt() : null;
                usage = new AiUsage(prompt, completion, total);
            }
            String model = root.path("model").asText(properties.getModel());
            return new AiChatResponse(content, properties.getProvider(), model, usage);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_RESPONSE_INVALID,
                    "AI provider returned an unreadable response", e);
        }
    }

    private static String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}
