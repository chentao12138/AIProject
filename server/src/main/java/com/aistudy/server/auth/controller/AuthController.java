package com.aistudy.server.auth.controller;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.dto.CurrentUserResponse;
import com.aistudy.server.auth.dto.LoginRequest;
import com.aistudy.server.auth.dto.LogoutRequest;
import com.aistudy.server.auth.dto.RefreshTokenRequest;
import com.aistudy.server.auth.dto.TokenPairResponse;
import com.aistudy.server.auth.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.util.List;

/**
 * BUSINESS-017 / BUSINESS-018 — auth endpoints.
 *
 * <p>This controller returns typed auth DTOs and never exposes
 * password hashes or refresh token hashes.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenPairResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenPairResponse response = authenticationService.login(request.username(), request.password());
        if (response == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new TokenPairResponse(null, "Bearer", 0, null, 0));
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenPairResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenPairResponse response = authenticationService.refresh(request.refreshToken());
        if (response == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new TokenPairResponse(null, "Bearer", 0, null, 0));
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authenticationService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public CurrentUserResponse me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null) {
            throw new BadCredentialsException("MISSING_AUTHENTICATION");
        }
        String subject = authentication.getName();
        UserAccount account = authenticationService.findAccountBySubject(subject);
        if (account == null) {
            throw new BadCredentialsException("MISSING_ACCOUNT");
        }
        List<String> roles = authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .toList();
        return new CurrentUserResponse(subject, account.getUsername(), roles);
    }
}
