package com.aistudy.server.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * BUSINESS-019 — domain conflict for admin mutations that would violate
 * last-active-admin safety.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class AdminConflictException extends RuntimeException {

    public AdminConflictException(String message) {
        super(message);
    }
}
