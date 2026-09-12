package com.aistudy.server.auth.service;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.entity.UserAccountRole;
import com.aistudy.server.auth.mapper.RefreshSessionMapper;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.mapper.UserAccountRoleMapper;
import com.aistudy.server.auth.security.AuthProperties;
import com.aistudy.server.auth.dto.AdminUserPageResponse;
import com.aistudy.server.auth.dto.AdminUserSummary;
import com.aistudy.server.auth.dto.AdminUserDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BUSINESS-019 — admin orchestration service.
 *
 * <p>Owns admin user management, status transitions, role mutation,
 * last-active-admin protection, and refresh-session revocation.
 */
@Service
public class AdminUserService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final UserAccountMapper userAccountMapper;
    private final UserAccountRoleMapper userAccountRoleMapper;
    private final RefreshSessionMapper refreshSessionMapper;
    private final UserRoleService userRoleService;
    private final RefreshTokenService refreshTokenService;
    private final AuthProperties authProperties;

    public AdminUserService(UserAccountMapper userAccountMapper,
                            UserAccountRoleMapper userAccountRoleMapper,
                            RefreshSessionMapper refreshSessionMapper,
                            UserRoleService userRoleService,
                            RefreshTokenService refreshTokenService,
                            AuthProperties authProperties) {
        this.userAccountMapper = userAccountMapper;
        this.userAccountRoleMapper = userAccountRoleMapper;
        this.refreshSessionMapper = refreshSessionMapper;
        this.userRoleService = userRoleService;
        this.refreshTokenService = refreshTokenService;
        this.authProperties = authProperties;
    }

    public AdminUserPageResponse listUsers(int page, int size) {
        long totalElements = userAccountMapper.countAll();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalElements / Math.max(1, size)));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int offset = safePage * size;
        List<UserAccount> accounts = userAccountMapper.selectPage(offset, size);
        List<AdminUserSummary> summaries = new ArrayList<>(accounts.size());
        for (UserAccount account : accounts) {
            List<String> roles = userRoleService.getRolesByUserAccountId(account.getId());
            summaries.add(new AdminUserSummary(
                    account.getId(),
                    account.getSubject(),
                    account.getUsername(),
                    account.getStatus(),
                    roles,
                    account.getCreatedAt()
            ));
        }
        return new AdminUserPageResponse(summaries, safePage, size, totalElements, totalPages);
    }

    public AdminUserDetail getUserDetail(String subject) {
        UserAccount account = userAccountMapper.selectBySubject(subject);
        if (account == null) {
            throw new UserNotFoundException("User not found: " + subject);
        }
        List<String> roles = userRoleService.getRolesByUserAccountId(account.getId());
        return new AdminUserDetail(
                account.getId(),
                account.getSubject(),
                account.getUsername(),
                account.getStatus(),
                roles,
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }

    @Transactional
    public void updateStatus(String subject, String targetStatus) {
        UserAccount account = userAccountMapper.selectBySubject(subject);
        if (account == null) {
            throw new UserNotFoundException("User not found: " + subject);
        }
        String currentStatus = account.getStatus();
        if (currentStatus.equals(targetStatus)) {
            return;
        }

        userAccountRoleMapper.selectAllAdminRolesForUpdate();
        if (isLastActiveAdmin(account.getId())) {
            throw new LastAdminProtectionException("Cannot change status of the last active ADMIN");
        }

        account.setStatus(targetStatus);
        account.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(account);

        if (STATUS_DISABLED.equals(targetStatus)) {
            refreshTokenService.revokeAllForUser(account.getId(), "ACCOUNT_DISABLED");
        }
    }

    @Transactional
    public void updateRoles(String subject, List<String> externalRoles) {
        UserAccount account = userAccountMapper.selectBySubject(subject);
        if (account == null) {
            throw new UserNotFoundException("User not found: " + subject);
        }
        if (!STATUS_ACTIVE.equals(account.getStatus())) {
            throw new IllegalArgumentException("Account is not ACTIVE");
        }
        List<UserAccountRole> locked = userAccountRoleMapper.selectLockRolesByUserAccountId(account.getId());
        userAccountRoleMapper.selectAllAdminRolesForUpdate();
        boolean currentlyActiveAdmin = false;
        for (UserAccountRole item : locked) {
            if (ROLE_ADMIN.equals(item.getRole()) && STATUS_ACTIVE.equals(account.getStatus())) {
                currentlyActiveAdmin = true;
                break;
            }
        }
        List<String> normalized = normalizeExternalRoles(externalRoles);
        boolean willBeAdmin = normalized.contains(ROLE_ADMIN);
        if (currentlyActiveAdmin && !willBeAdmin && isLastActiveAdmin(account.getId())) {
            throw new LastAdminProtectionException("Cannot remove ADMIN from the last active ADMIN");
        }

        userRoleService.replaceRoles(account.getId(), normalized);
        if (rolesChanged(locked, normalized)) {
            refreshTokenService.revokeAllForUser(account.getId(), "ROLE_CHANGED");
        }
    }

    private boolean isLastActiveAdmin(Long userAccountId) {
        if (!userRoleService.isAdmin(userAccountId)) {
            return false;
        }
        UserAccount account = userAccountMapper.selectById(userAccountId);
        if (account == null || !STATUS_ACTIVE.equals(account.getStatus())) {
            return false;
        }
        return userAccountRoleMapper.countActiveAdminAccounts() <= 1;
    }

    private List<String> normalizeExternalRoles(List<String> externalRoles) {
        List<String> normalized = new ArrayList<>();
        if (externalRoles == null) {
            return List.of(ROLE_USER);
        }
        Set<String> seen = new HashSet<>();
        for (String role : externalRoles) {
            if (role == null) {
                continue;
            }
            String trimmed = role.trim().toUpperCase();
            if (!Set.of(ROLE_USER, ROLE_ADMIN).contains(trimmed)) {
                throw new IllegalArgumentException("Unsupported role: " + role);
            }
            if (!seen.contains(trimmed)) {
                seen.add(trimmed);
                normalized.add(trimmed);
            }
        }
        if (normalized.isEmpty()) {
            return List.of(ROLE_USER);
        }
        if (!normalized.contains(ROLE_USER)) {
            normalized.add(0, ROLE_USER);
        }
        return normalized;
    }

    private boolean rolesChanged(List<UserAccountRole> locked, List<String> normalized) {
        Set<String> existing = new HashSet<>();
        for (UserAccountRole item : locked) {
            existing.add(item.getRole());
        }
        return !existing.equals(new HashSet<>(normalized));
    }
}
