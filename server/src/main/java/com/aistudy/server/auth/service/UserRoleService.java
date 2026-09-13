package com.aistudy.server.auth.service;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.entity.UserAccountRole;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.mapper.UserAccountRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BUSINESS-019 — role persistence service.
 *
 * <p>Owns role reads/mutations so controllers do not embed role SQL.
 */
@Service
public class UserRoleService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final Set<String> SUPPORTED_ROLES = Set.of(ROLE_USER, ROLE_ADMIN);

    private final UserAccountRoleMapper roleMapper;
    private final UserAccountMapper userAccountMapper;

    public UserRoleService(UserAccountRoleMapper roleMapper,
                           UserAccountMapper userAccountMapper) {
        this.roleMapper = roleMapper;
        this.userAccountMapper = userAccountMapper;
    }

    public List<String> getRolesByUserAccountId(Long userAccountId) {
        List<UserAccountRole> roles = roleMapper.selectByUserAccountId(userAccountId);
        List<String> result = new ArrayList<>(roles.size());
        for (UserAccountRole role : roles) {
            result.add(role.getRole());
        }
        return result;
    }

    public List<String> getRolesBySubject(String subject) {
        UserAccount account = userAccountMapper.selectBySubject(subject);
        if (account == null) {
            return List.of();
        }
        return getRolesByUserAccountId(account.getId());
    }

    public boolean hasRole(Long userAccountId, String role) {
        List<UserAccountRole> locked = roleMapper.selectLockRolesByUserAccountId(userAccountId);
        for (UserAccountRole item : locked) {
            if (role.equals(item.getRole())) {
                return true;
            }
        }
        return false;
    }

    public boolean isAdmin(Long userAccountId) {
        return hasRole(userAccountId, ROLE_ADMIN);
    }

    public long countActiveAdminAccounts() {
        return roleMapper.countActiveAdminAccounts();
    }

    public void ensureUserRole(Long userAccountId) {
        List<UserAccountRole> locked = roleMapper.selectLockRolesByUserAccountId(userAccountId);
        boolean hasUser = false;
        for (UserAccountRole item : locked) {
            if (ROLE_USER.equals(item.getRole())) {
                hasUser = true;
            }
        }
        if (!hasUser) {
            UserAccountRole role = new UserAccountRole();
            role.setUserAccountId(userAccountId);
            role.setRole(ROLE_USER);
            role.setCreatedAt(LocalDateTime.now());
            roleMapper.insert(role);
        }
    }

    @Transactional
    public void replaceRoles(Long userAccountId, List<String> roles) {
        List<String> normalized = normalizeExternalRoles(roles);
        if (!normalized.contains(ROLE_USER)) {
            throw new IllegalArgumentException("USER role cannot be removed");
        }

        List<UserAccountRole> locked = roleMapper.selectLockRolesByUserAccountId(userAccountId);
        List<UserAccountRole> toDelete = new ArrayList<>();
        List<UserAccountRole> toInsert = new ArrayList<>();
        boolean adminChanged = false;

        for (UserAccountRole existing : locked) {
            if (!normalized.contains(existing.getRole())) {
                toDelete.add(existing);
                if (ROLE_ADMIN.equals(existing.getRole())) {
                    adminChanged = true;
                }
            }
        }

        Set<String> existingRoles = new HashSet<>();
        for (UserAccountRole existing : locked) {
            existingRoles.add(existing.getRole());
        }
        for (String role : normalized) {
            if (!existingRoles.contains(role)) {
                UserAccountRole created = new UserAccountRole();
                created.setUserAccountId(userAccountId);
                created.setRole(role);
                created.setCreatedAt(LocalDateTime.now());
                toInsert.add(created);
                if (ROLE_ADMIN.equals(role)) {
                    adminChanged = true;
                }
            }
        }

        if (adminChanged && !isCurrentlyActiveAdmin(userAccountId) && normalized.contains(ROLE_ADMIN)) {
            // no-op: adding admin while currently not admin is allowed.
        }

        for (UserAccountRole item : toDelete) {
            roleMapper.deleteById(item.getId());
        }
        for (UserAccountRole item : toInsert) {
            roleMapper.insert(item);
        }
    }

    private boolean isCurrentlyActiveAdmin(Long userAccountId) {
        UserAccount account = userAccountMapper.selectById(userAccountId);
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            return false;
        }
        return isAdmin(userAccountId);
    }

    private List<String> normalizeExternalRoles(List<String> roles) {
        List<String> normalized = new ArrayList<>();
        if (roles == null) {
            return List.of(ROLE_USER);
        }
        for (String role : roles) {
            if (role == null) {
                continue;
            }
            String trimmed = role.trim().toUpperCase();
            if (!SUPPORTED_ROLES.contains(trimmed)) {
                throw new IllegalArgumentException("Unsupported role: " + role);
            }
            if (!normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        if (normalized.isEmpty()) {
            return List.of(ROLE_USER);
        }
        return normalized;
    }
}
