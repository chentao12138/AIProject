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
