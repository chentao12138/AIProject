package com.aistudy.server.auth.service;

import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

/**
 * BUSINESS-019 — thrown when an operation would leave the platform
 * with zero active ADMIN accounts.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class LastAdminProtectionException extends RuntimeException {

    public LastAdminProtectionException(String message) {
        super(message);
    }
}
