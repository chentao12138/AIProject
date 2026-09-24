package com.aistudy.server.common.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * The single place HTTP error bodies are produced (api-guidelines.md §12).
 *
 * <p>RFC 7807 {@code application/problem+json} carrying a stable {@code code}
 * plus the {@code requestId} already present in MDC, so clients branch on
 * {@code code} instead of free-text {@code detail}.
 *
 * <p>Legacy {@link ResponseStatusException} call sites keep their status code
 * and reason here; new code should throw {@link ApiException} to pin an
 * explicit code.
 *
 * <p>Messages of unhandled internal failures are never echoed: they stay in
 * the log line sharing the response {@code requestId}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex, HttpServletRequest request) {
        return problem(ex.status().value(), ex.code(), ex.detail(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        int status = ex.getStatusCode().value();
        String detail = ex.getReason() == null ? fallbackDetail(status) : ex.getReason();
        return problem(status, ApiErrorCodes.forStatus(status), detail, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyValidation(MethodArgumentNotValidException ex,
                                              HttpServletRequest request) {
        ProblemDetail detail = problem(400, ApiErrorCodes.VALIDATION_ERROR,
                "Request validation failed", request);
        detail.setProperty("errors", fieldErrors(ex.getBindingResult().getFieldErrors()));
        return detail;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(HandlerMethodValidationException ex,
                                                HttpServletRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> errors.add(new ValidationError(
                    field == null ? "value" : field,
                    error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage())));
        });
        ProblemDetail detail = problem(400, ApiErrorCodes.VALIDATION_ERROR,
                "Request validation failed", request);
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex,
                                                   HttpServletRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.add(new ValidationError(violation.getPropertyPath().toString(), violation.getMessage()));
        }
        ProblemDetail detail = problem(400, ApiErrorCodes.VALIDATION_ERROR,
                "Request validation failed", request);
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Denied request requestId={} path={}", requestId(), request.getRequestURI());
        return problem(403, ApiErrorCodes.FORBIDDEN, "当前会话无权执行此操作。", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problem(401, ApiErrorCodes.UNAUTHORIZED, "未认证或凭据无效。", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Rejected request requestId={} path={} reason={}",
                requestId(), request.getRequestURI(), ex.toString());
        return problem(400, ApiErrorCodes.VALIDATION_ERROR, "请求包含无法处理的参数。", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.error("Unhandled server state requestId={} path={}", requestId(), request.getRequestURI(), ex);
        return problem(500, ApiErrorCodes.INTERNAL_ERROR, "服务器暂时无法完成请求，请稍后重试。", request);
    }

    private ProblemDetail problem(int status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problemDetail.setTitle(titleFor(status));
        URI instance = instanceFor(request);
        if (instance != null) {
            problemDetail.setInstance(instance);
        }
        problemDetail.setProperty("code", code);
        String requestId = requestId();
        if (requestId != null) {
            problemDetail.setProperty("requestId", requestId);
        }
        return problemDetail;
    }

    private String titleFor(int status) {
        var resolved = org.springframework.http.HttpStatus.resolve(status);
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }

    private String fallbackDetail(int status) {
        var resolved = org.springframework.http.HttpStatus.resolve(status);
        return resolved == null ? "Request failed" : resolved.getReasonPhrase();
    }

    private URI instanceFor(HttpServletRequest request) {
        try {
            return new URI(request.getRequestURI());
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private String requestId() {
        return MDC.get(REQUEST_ID_MDC_KEY);
    }

    private List<ValidationError> fieldErrors(List<FieldError> fieldErrors) {
        List<ValidationError> errors = new ArrayList<>(fieldErrors.size());
        for (FieldError error : fieldErrors) {
            errors.add(new ValidationError(error.getField(), error.getDefaultMessage()));
        }
        return errors;
    }

    /** One field-level failure inside the §12 {@code errors[]} array. */
    public record ValidationError(String field, String message) {
    }
}
