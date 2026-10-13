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
    /**
     * A second ingestion job was submitted for an asset whose first job is
     * still in flight. Distinct from {@link #CONFLICT} so the Desktop client
     * can offer "wait for the running job" instead of parsing English text.
     */
    public static final String INGESTION_ALREADY_RUNNING = "INGESTION_ALREADY_RUNNING";
    public static final String CONTENT_NOT_PUBLISHED = "CONTENT_NOT_PUBLISHED";
    public static final String EXAM_NOT_AVAILABLE = "EXAM_NOT_AVAILABLE";
    public static final String EXAM_ATTEMPT_NOT_IN_PROGRESS = "EXAM_ATTEMPT_NOT_IN_PROGRESS";
    public static final String EXAM_DEADLINE_EXCEEDED = "EXAM_DEADLINE_EXCEEDED";

    /**
     * Exam conflict codes (contract request C12 / audit F-30). Every one of
     * these arrives as HTTP 409 today; the code is the only thing a client may
     * branch on, because {@code detail} is display text and is not a contract.
     */
    /** Start was requested while an attempt of this exam is still open. */
    public static final String EXAM_ACTIVE_ATTEMPT_EXISTS = "EXAM_ACTIVE_ATTEMPT_EXISTS";
    /** The attempt had already been submitted when it was submitted again. */
    public static final String EXAM_ATTEMPT_ALREADY_SUBMITTED = "EXAM_ATTEMPT_ALREADY_SUBMITTED";
    /** Result/grading was requested before the attempt was submitted. */
    public static final String EXAM_RESULT_NOT_AVAILABLE = "EXAM_RESULT_NOT_AVAILABLE";
    /** A submitted attempt has no persisted result row — a data defect, not user input. */
    public static final String EXAM_RESULT_MISSING = "EXAM_RESULT_MISSING";
    /** Grading was requested for an attempt that is not in a gradable state. */
    public static final String EXAM_GRADING_NOT_AVAILABLE = "EXAM_GRADING_NOT_AVAILABLE";
    /** This answer cannot be graded (e.g. already graded or not stored). */
    public static final String EXAM_ANSWER_NOT_GRADABLE = "EXAM_ANSWER_NOT_GRADABLE";
    /** Another request moved the attempt on between read and write. */
    public static final String EXAM_ATTEMPT_STALE = "EXAM_ATTEMPT_STALE";
    /** Another request moved the answer on between read and write. */
    public static final String EXAM_ANSWER_STALE = "EXAM_ANSWER_STALE";
    public static final String AI_NOT_CONFIGURED = "AI_NOT_CONFIGURED";
    public static final String AI_PROVIDER_UNAVAILABLE = "AI_PROVIDER_UNAVAILABLE";

    /**
     * The practice result was requested while the session is still open
     * (C06). {@code CONFLICT} alone cannot tell the client "answer more
     * questions" from "this session belongs to another space".
     */
    public static final String PRACTICE_SESSION_NOT_SUBMITTED = "PRACTICE_SESSION_NOT_SUBMITTED";
    /** The session predates V066 and therefore has no stored summary. */
    public static final String PRACTICE_RESULT_NOT_RECORDED = "PRACTICE_RESULT_NOT_RECORDED";

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
