package com.aistudy.server.common.problem;

import org.springframework.http.HttpStatus;

/**
 * Business error that carries the stable machine-readable {@code code}
 * required by api-guidelines.md §12. Throwing this from any layer renders
 * as {@code application/problem+json} through {@link ApiExceptionHandler}.
 *
 * <p>{@code detail} is client-safe by construction: it is the only message
 * text that reaches the response, so internal causes must stay in the log
 * (see {@link #ApiException(HttpStatus, String, String, Throwable)}).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, null);
    }

    public ApiException(HttpStatus status, String code, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.code = code;
    }

    /**
     * 409 with a stable code. Exists because a bare
     * {@code ResponseStatusException(CONFLICT, "...(EXAM_NOT_AVAILABLE)...")}
     * hides the code inside English prose, which a client cannot branch on.
     */
    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    /**
     * 404 with a stable code. The status stays 404 on purpose: an ownership or
     * space mismatch must not reveal that the resource exists elsewhere.
     */
    public static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return getMessage();
    }
}
