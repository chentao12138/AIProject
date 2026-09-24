package com.aistudy.server.auth.service;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.security.AuthProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserAccountMapper userAccountMapper;
    private final AuthenticationService authenticationService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;
    private final UserRoleService userRoleService;
    private final RefreshTokenService refreshTokenService;

    public UserAccountService(UserAccountMapper userAccountMapper,
                              AuthenticationService authenticationService,
                              PasswordEncoder passwordEncoder,
                              AuthProperties authProperties,
                              UserRoleService userRoleService,
                              RefreshTokenService refreshTokenService) {
        this.userAccountMapper = userAccountMapper;
        this.authenticationService = authenticationService;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
        this.userRoleService = userRoleService;
        this.refreshTokenService = refreshTokenService;
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

    @Transactional
    public void changePassword(String subject, String currentPassword, String newPassword) {
        UserAccount account = userAccountMapper.selectBySubject(subject);
        if (account == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "User not found");
        }
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        account.setUpdatedAt(java.time.LocalDateTime.now());
        userAccountMapper.updateById(account);
        refreshTokenService.revokeAllForUser(account.getId(), "PASSWORD_CHANGED");
    }

    @Transactional
    public void resetPassword(String subject, String newPassword) {
        UserAccount account = userAccountMapper.selectBySubject(subject);
        if (account == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "User not found");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        account.setUpdatedAt(java.time.LocalDateTime.now());
        userAccountMapper.updateById(account);
        refreshTokenService.revokeAllForUser(account.getId(), "PASSWORD_RESET");
    }

    public long getAccessTokenTtlSeconds() {
        return authProperties.getJwt().getAccessTokenTtlSeconds();
    }
}
