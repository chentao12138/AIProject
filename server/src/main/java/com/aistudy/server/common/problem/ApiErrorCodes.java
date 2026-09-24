package com.aistudy.server.common.problem;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error codes from api-guidelines.md §12, plus the
 * default code derived from an HTTP status when a legacy
 * {@link org.springframework.web.server.ResponseStatusException} carries no
 * code of its own.
 *
 * <p>Clients must branch on these values, never on {@code detail} text.
 */
public final class ApiErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public static final String SPACE_NOT_FOUND = "SPACE_NOT_FOUND";
    public static final String SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
    public static final String QUESTION_NOT_FOUND = "QUESTION_NOT_FOUND";
    public static final String INGESTION_NOT_READY = "INGESTION_NOT_READY";
    public static final String CONTENT_NOT_PUBLISHED = "CONTENT_NOT_PUBLISHED";
    public static final String EXAM_NOT_AVAILABLE = "EXAM_NOT_AVAILABLE";
    public static final String EXAM_ATTEMPT_NOT_IN_PROGRESS = "EXAM_ATTEMPT_NOT_IN_PROGRESS";
    public static final String EXAM_DEADLINE_EXCEEDED = "EXAM_DEADLINE_EXCEEDED";
    public static final String AI_NOT_CONFIGURED = "AI_NOT_CONFIGURED";
    public static final String AI_PROVIDER_UNAVAILABLE = "AI_PROVIDER_UNAVAILABLE";

    private ApiErrorCodes() {
    }

    /** Fallback code for statuses produced outside {@link ApiException}. */
    public static String forStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_ERROR;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            default -> {
                HttpStatus resolved = HttpStatus.resolve(status);
                yield resolved == null ? "HTTP_" + status : resolved.name();
            }
        };
    }
}
