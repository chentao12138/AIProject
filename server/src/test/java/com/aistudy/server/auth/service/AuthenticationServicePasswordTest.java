package com.aistudy.server.auth.service;

import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.security.AuthProperties;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import com.aistudy.server.auth.service.RefreshTokenService;
import com.aistudy.server.auth.service.UserRoleService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationServicePasswordTest {

    private final PasswordEncoder encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    private final AuthenticationService service = new AuthenticationService(
            null,
            null,
            null,
            null,
            null,
            encoder,
            new AuthProperties()
    );

    @Test
    void hashAndVerifyRoundTrip() {
        String raw = "test-P***word-123";
        String hash = encoder.encode(raw);

        assertTrue(encoder.matches(raw, hash));
        assertFalse(encoder.matches(raw + "x", hash));
        assertFalse(encoder.matches("", hash));
    }
}
