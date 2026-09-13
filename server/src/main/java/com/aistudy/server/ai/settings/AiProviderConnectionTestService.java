package com.aistudy.server.ai.settings;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiErrorCode;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.provider.AiProviderException;
import com.aistudy.server.ai.settings.AiSettingsDto.TestConnectionResponse;
import com.aistudy.server.operations.AiMetrics;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * AI-009 — backend-originated connectivity test for the CURRENT user.
 * Never persists the test prompt as a Tutor conversation.
 */
@Service
public class AiProviderConnectionTestService {

    private final AiProvider aiProvider;
    private final AiRuntimeConfigResolver configResolver;
    private final AiMetrics aiMetrics;

    public AiProviderConnectionTestService(AiProvider aiProvider,
                                           AiRuntimeConfigResolver configResolver,
                                           AiMetrics aiMetrics) {
        this.aiProvider = aiProvider;
        this.configResolver = configResolver;
        this.aiMetrics = aiMetrics;
    }

    public TestConnectionResponse testConnection(String userSubject) {
        AiRuntimeConfigResolver.ResolvedConfig config = configResolver.resolveForUser(userSubject);
        if (!config.isConfigured()) {
            aiMetrics.recordFailure(
                    config.provider() == null ? "UNKNOWN" : config.provider(),
                    AiErrorCode.AI_NOT_CONFIGURED.name());
            throw new AiProviderException(AiErrorCode.AI_NOT_CONFIGURED,
                    "AI provider is not configured");
        }
        long start = System.currentTimeMillis();
        try {
            // 64 tokens: reasoning models (e.g. step-3.5-flash) spend the
            // budget on hidden reasoning before emitting visible content.
            AiChatResponse response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.user("Reply with the single word: ok")),
                    0.0,
                    64), userSubject);
            long latency = System.currentTimeMillis() - start;
            aiMetrics.recordRequest(config.provider(), "SUCCESS");
            String content = response == null || response.content() == null
                    ? "" : response.content().trim();
            if (content.isEmpty()) {
                throw new AiProviderException(AiErrorCode.AI_PROVIDER_RESPONSE_INVALID,
                        "AI provider returned an empty test response");
            }
            return new TestConnectionResponse(true, config.provider(), config.model(), latency);
        } catch (AiProviderException e) {
            aiMetrics.recordFailure(config.provider(), e.errorCode().name());
            throw e;
        }
    }
}
