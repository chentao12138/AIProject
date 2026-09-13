package com.aistudy.server.ai.provider;

/**
 * AI-001 — stable AI error codes for business HTTP mapping.
 * Never include upstream bodies, API keys, or stack traces in API responses.
 */
public enum AiErrorCode {
    AI_NOT_CONFIGURED,
    AI_PROVIDER_TIMEOUT,
    AI_PROVIDER_UNAVAILABLE,
    AI_PROVIDER_REJECTED,
    AI_PROVIDER_RESPONSE_INVALID
}
