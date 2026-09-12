package com.aistudy.server.auth.service;

import com.aistudy.server.auth.entity.RefreshSession;
import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.RefreshSessionMapper;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.security.AuthProperties;
import com.aistudy.server.auth.dto.TokenPairResponse;
import com.aistudy.server.auth.service.JwtAccessTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-018 — account lifecycle and login/refresh/logout service.
 *
 * <p>This service owns credential verification, access token issuance,
 * refresh session creation, rotation, reuse detection, and logout.
 */
@Service
public class AuthenticationService {

    private static final String REVOKE_REASON_REUSE = "REUSE_DETECTED";
    private static final String REVOKE_REASON_LOGOUT = "LOGOUT";

    private final UserAccountMapper userAccountMapper;
    private final RefreshSessionMapper refreshSessionMapper;
    private final RefreshTokenService refreshTokenService;
    private final JwtAccessTokenService jwtAccessTokenService;
    private final UserRoleService userRoleService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    public AuthenticationService(UserAccountMapper userAccountMapper,
                                 RefreshSessionMapper refreshSessionMapper,
                                 RefreshTokenService refreshTokenService,
                                 JwtAccessTokenService jwtAccessTokenService,
                                 UserRoleService userRoleService,
                                 PasswordEncoder passwordEncoder,
                                 AuthProperties authProperties) {
        this.userAccountMapper = userAccountMapper;
        this.refreshSessionMapper = refreshSessionMapper;
        this.refreshTokenService = refreshTokenService;
        this.jwtAccessTokenService = jwtAccessTokenService;
        this.userRoleService = userRoleService;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
    }

    public String generateSubject() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public UserAccount newAccount(String username) {
        UserAccount account = new UserAccount();
        account.setSubject(generateSubject());
        account.setUsername(username);
        account.setStatus("ACTIVE");
        account.setCreatedAt(java.time.LocalDateTime.now());
        account.setUpdatedAt(account.getCreatedAt());
        return account;
    }

    public UserAccount findAccountByUsername(String username) {
        return userAccountMapper.selectByUsername(username);
    }

    public UserAccount findAccountBySubject(String subject) {
        return userAccountMapper.selectBySubject(subject);
    }

    @Transactional
    public String createAccount(String username, String rawPassword) {
        UserAccount account = newAccount(username);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        userAccountMapper.insert(account);
        userRoleService.ensureUserRole(account.getId());
        return account.getSubject();
    }

    @Transactional
    public TokenPairResponse login(String username, String rawPassword) {
        UserAccount account = userAccountMapper.selectByUsername(username);
        if (!isActive(account) || !matchesPassword(rawPassword, account)) {
            return null;
        }
        return issuePair(account);
    }

    @Transactional
    public TokenPairResponse refresh(String refreshToken) {
        String tokenHash = refreshTokenService.hash(refreshToken);
        LocalDateTime now = refreshTokenService.toDatabaseDateTime(Instant.now());
        RefreshSession session = refreshSessionMapper.selectActiveByTokenHash(tokenHash, now);

        if (session == null) {
            return null;
        }

        UserAccount account = userAccountMapper.selectById(session.getUserAccountId());
        if (!isActive(account)) {
            return null;
        }

        if (isSessionReused(session)) {
            revokeFamily(session.getFamilyId(), now, REVOKE_REASON_REUSE, session.getId());
            return null;
        }

        String rotatedPlaintextToken = refreshTokenService.generateToken();
        RefreshSession rotated = buildRotatedSession(session, now, rotatedPlaintextToken);
        refreshSessionMapper.insert(rotated);

        RefreshSession update = new RefreshSession();
        update.setId(session.getId());
        update.setRotatedAt(now);
        update.setUpdatedAt(now);
        refreshSessionMapper.updateById(update);

        return buildResponse(account, rotated, rotatedPlaintextToken);
    }

    @Transactional
    public void logout(String refreshToken) {
        String tokenHash = refreshTokenService.hash(refreshToken);
        LocalDateTime now = refreshTokenService.toDatabaseDateTime(Instant.now());
        RefreshSession session = refreshSessionMapper.selectActiveByTokenHash(tokenHash, now);

        if (session == null) {
            return;
        }

        if (isSessionReused(session)) {
            revokeFamily(session.getFamilyId(), now, REVOKE_REASON_REUSE, session.getId());
            return;
        }

        revokeSession(session, now, REVOKE_REASON_LOGOUT);
        refreshSessionMapper.updateById(session);
    }

    public List<String> getRolesForCurrentUser(Long userAccountId) {
        return userRoleService.getRolesByUserAccountId(userAccountId);
    }

    private TokenPairResponse issuePair(UserAccount account) {
        String refreshToken = refreshTokenService.generateToken();
        RefreshSession session = buildActiveSession(account, refreshToken);
        refreshSessionMapper.insert(session);
        return buildResponse(account, session, refreshToken);
    }

    private RefreshSession buildActiveSession(UserAccount account, String refreshToken) {
        Instant now = Instant.now();
        RefreshSession session = new RefreshSession();
        session.setUserAccountId(account.getId());
        session.setFamilyId(nextFamilyId());
        session.setTokenHash(refreshTokenService.hash(refreshToken));
        session.setIssuedAt(refreshTokenService.toDatabaseDateTime(now));
        session.setExpiresAt(refreshTokenService.toDatabaseDateTime(
                now.plusSeconds(refreshTokenService.getTtlSeconds())));
        session.setCreatedAt(session.getIssuedAt());
        session.setUpdatedAt(session.getIssuedAt());
        return session;
    }

    private RefreshSession buildRotatedSession(RefreshSession session, LocalDateTime now, String refreshToken) {
        RefreshSession rotated = new RefreshSession();
        rotated.setUserAccountId(session.getUserAccountId());
        rotated.setFamilyId(session.getFamilyId());
        rotated.setTokenHash(refreshTokenService.hash(refreshToken));
        rotated.setIssuedAt(now);
        rotated.setExpiresAt(now.plusSeconds(refreshTokenService.getTtlSeconds()));
        rotated.setCreatedAt(now);
        rotated.setUpdatedAt(now);
        return rotated;
    }

    private TokenPairResponse buildResponse(UserAccount account,
                                            RefreshSession session,
                                            String plaintextRefreshToken) {
        java.util.List<String> roles = userRoleService.getRolesByUserAccountId(account.getId());
        return new TokenPairResponse(
                jwtAccessTokenService.issueAccessToken(account.getSubject(), roles),
                "Bearer",
                authProperties.getJwt().getAccessTokenTtlSeconds(),
                plaintextRefreshToken,
                refreshTokenService.getTtlSeconds()
        );
    }

    private void revokeSession(RefreshSession session, LocalDateTime now, String reason) {
        session.setRevokedAt(now);
        session.setRevokeReason(reason);
        session.setUpdatedAt(now);
    }

    private void revokeFamily(long familyId, LocalDateTime now, String reason, Long excludeId) {
        RefreshSession revoke = new RefreshSession();
        revoke.setRevokedAt(now);
        revoke.setRevokeReason(reason);
        revoke.setUpdatedAt(now);
        refreshSessionMapper.revokeFamilyActive(familyId, now, reason, excludeId);
    }

    private boolean isActive(UserAccount account) {
        return account != null && "ACTIVE".equals(account.getStatus());
    }

    private boolean matchesPassword(String rawPassword, UserAccount account) {
        String passwordHash = account == null ? null : account.getPasswordHash();
        return passwordHash != null && passwordEncoder.matches(rawPassword, passwordHash);
    }

    private boolean isSessionReused(RefreshSession session) {
        return session.getRotatedAt() != null;
    }

    private long nextFamilyId() {
        return Instant.now().toEpochMilli();
    }
}
