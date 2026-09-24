package com.aistudy.server.auth.controller;

import com.aistudy.server.auth.service.UserAccountService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@SecurityRequirement(name = "bearerAuth")
public class ChangePasswordController {

    private final UserAccountService userAccountService;

    public ChangePasswordController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @PutMapping(value = "/me/password", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request,
                               Authentication authentication) {
        String subject = authentication.getName();
        userAccountService.changePassword(subject, request.currentPassword(), request.newPassword());
    }
}
