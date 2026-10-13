package com.aistudy.server.ai.provider;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.ai.settings.AiRuntimeConfigResolver;
import com.aistudy.server.ai.settings.AiRuntimeConfigResolver.ResolvedConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI-001 / AI-009 — OpenAI-compatible Chat Completions adapter.
 *
 * <p>Vendor-neutral (OpenAI, StepFun, …). Effective config is resolved
 * per call from runtime settings + secret store with env fallback.
 * Vendor DTOs and the API key never leave this class.
 */
@Component
public class OpenAiCompatibleAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAiProvider.class);

    private final AiRuntimeConfigResolver configResolver;
    private final AiProperties envProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleAiProvider(AiRuntimeConfigResolver configResolver,
                                      AiProperties envProperties,
                                      ObjectMapper objectMapper) {
        this.configResolver = configResolver;
        this.envProperties = envProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(envProperties.getConnectTimeout())
                .build();
    }

    @Override
    public AiChatResponse chat(AiChatRequest request, String userSubject) {
        ResolvedConfig config = configResolver.resolveForUser(userSubject);
        if (!config.isConfigured()) {
            throw new AiProviderException(AiErrorCode.AI_NOT_CONFIGURED,
                    "AI provider is not configured");
        }
        String endpoint = joinUrl(config.baseUrl(), "/chat/completions");
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(config.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(request, config)))
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
        if (status >= 400) {
            // An upstream 4xx was previously logged as a bare status code, so an
            // invalid BYOK key, a rejected sampling parameter and a quota reply all
            // looked identical from the server. The provider's own message is what
            // distinguishes them; it stays in the log and never reaches the client.
            log.warn("AI provider call failed status={} host={} model={} body={}",
                    status, URI.create(endpoint).getHost(), config.model(),
                    safeExcerpt(response.body(), config.apiKey()));
        }
        if (status == 401 || status == 403 || status == 429 || (status >= 400 && status < 500)) {
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_REJECTED,
                    "AI provider rejected the request");
        }
        if (status < 200 || status >= 300) {
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "AI provider is unavailable");
        }
        return parseResponse(response.body(), config);
    }

    /** Bounded single-line view of an upstream error body, with the key redacted. Log-only. */
    private static String safeExcerpt(String body, String secret) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String redacted = secret == null || secret.isBlank()
                ? body
                : body.replace(secret, "***");
        String flat = redacted.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) : flat;
    }

    private String buildBody(AiChatRequest request, ResolvedConfig config) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("temperature", request.temperature() != null
                ? request.temperature() : config.temperature());
        int maxTokens = request.maxOutputTokens() != null
                ? request.maxOutputTokens() : config.maxOutputTokens();
        root.put("max_tokens", maxTokens);
        root.put("stream", false);
        ArrayNode messages = root.putArray("messages");
        List<AiChatMessage> safe = request.messages() == null ? List.of() : request.messages();
        for (AiChatMessage message : safe) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role().name().toLowerCase());
            node.put("content", message.content() == null ? "" : message.content());
        }
        return root.toString();
    }

    private AiChatResponse parseResponse(String body, ResolvedConfig config) {
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
            String model = root.path("model").asText(config.model());
            return new AiChatResponse(content, config.provider(), model, usage);
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
