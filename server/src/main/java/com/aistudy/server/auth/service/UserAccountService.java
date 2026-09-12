package com.aistudy.server.auth.service;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.security.AuthProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BUSINESS-017 — account lifecycle and login verification service.
 *
 * <p>Owns the account creation + login verification flow so the
 * controller does not repeat BCrypt / disabled checks.
 */
@Service
public class UserAccountService {

    private final UserAccountMapper userAccountMapper;
    private final AuthenticationService authenticationService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;
    private final UserRoleService userRoleService;

    public UserAccountService(UserAccountMapper userAccountMapper,
                              AuthenticationService authenticationService,
                              PasswordEncoder passwordEncoder,
                              AuthProperties authProperties,
                              UserRoleService userRoleService) {
        this.userAccountMapper = userAccountMapper;
        this.authenticationService = authenticationService;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
        this.userRoleService = userRoleService;
    }

    @Transactional
    public String createAccount(String username, String rawPassword) {
        UserAccount account = authenticationService.newAccount(username);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        userAccountMapper.insert(account);
        userRoleService.ensureUserRole(account.getId());
        return account.getSubject();
    }

    public String verifyLogin(String username, String rawPassword) {
        UserAccount account = userAccountMapper.selectByUsername(username);
        if (!"ACTIVE".equals(account == null ? null : account.getStatus())) {
            return null;
        }
        String passwordHash = account == null ? null : account.getPasswordHash();
        if (passwordHash == null || !passwordEncoder.matches(rawPassword, passwordHash)) {
            return null;
        }
        return account.getSubject();
    }

    public UserAccount findAccountBySubject(String subject) {
        return userAccountMapper.selectBySubject(subject);
    }

    public long getAccessTokenTtlSeconds() {
        return authProperties.getJwt().getAccessTokenTtlSeconds();
    }
}
