package com.aistudy.server.ai.provider;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI-001 — business-safe AI failure mapped to stable HTTP statuses.
 * Root cause stays server-side only (never in the response body).
 */
public class AiProviderException extends ResponseStatusException {

    private final AiErrorCode errorCode;

    public AiProviderException(AiErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, defaultStatus(errorCode), null);
    }

    public AiProviderException(AiErrorCode errorCode, String safeMessage, Throwable cause) {
        this(errorCode, safeMessage, defaultStatus(errorCode), cause);
    }

    private AiProviderException(AiErrorCode errorCode,
                                String safeMessage,
                                HttpStatus httpStatus,
                                Throwable cause) {
        super(httpStatus, safeMessage, cause);
        this.errorCode = errorCode;
    }

    public AiErrorCode errorCode() {
        return errorCode;
    }

    private static HttpStatus defaultStatus(AiErrorCode code) {
        return switch (code) {
            case AI_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_PROVIDER_TIMEOUT, AI_PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_PROVIDER_REJECTED -> HttpStatus.BAD_GATEWAY;
            case AI_PROVIDER_RESPONSE_INVALID -> HttpStatus.BAD_GATEWAY;
        };
    }
}
