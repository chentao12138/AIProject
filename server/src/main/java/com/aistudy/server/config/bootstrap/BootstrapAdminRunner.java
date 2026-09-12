package com.aistudy.server.config.bootstrap;

import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.config.properties.BootstrapAdminProperties;
import com.aistudy.server.auth.service.UserAccountService;
import com.aistudy.server.auth.service.UserRoleService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;

/**
 * BUSINESS-026 first-admin bootstrap runner.
 *
 * <p>Runs after the schema and repositories are ready and creates
 * the first ADMIN account when explicitly enabled.
 *
 * <p>Semantics:
 *
 * <ul>
 *   <li>enabled=true + valid fields + no admin -> create account with USER+ADMIN</li>
 *   <li>enabled=true + admin already exists -> no-op</li>
 *   <li>enabled=true + missing fields -> fail startup</li>
 *   <li>enabled=false -> no-op</li>
 * </ul>
 *
 * <p>Concurrency: if create fails because another instance won the
 * race, the runner re-checks ADMIN existence and no-ops if present.
 */
@Component
@Order(1)
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";

    private final BootstrapAdminProperties properties;
    private final UserAccountService userAccountService;
    private final UserRoleService userRoleService;
    private final UserAccountMapper userAccountMapper;

    public BootstrapAdminRunner(BootstrapAdminProperties properties,
                               UserAccountService userAccountService,
                               UserRoleService userRoleService,
                               UserAccountMapper userAccountMapper) {
        this.properties = properties;
        this.userAccountService = userAccountService;
        this.userRoleService = userRoleService;
        this.userAccountMapper = userAccountMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }

        String username = properties.getUsername();
        String password = properties.getPassword();

        if (username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalStateException(String.format(
                    "Bootstrap admin fields are missing or blank; "
                            + "required=[username, password]; actual=[%s, %s]",
                    safeField(username),
                    safeField(password)));
        }

        if (userRoleService.countActiveAdminAccounts() > 0) {
            return;
        }

        String subject = userAccountService.createAccount(username.trim(), password);
        UserAccount account = userAccountMapper.selectBySubject(subject);
        if (account == null) {
            throw new IllegalStateException(
                    "Bootstrap admin account was created but could not be loaded");
        }

        try {
            userRoleService.replaceRoles(
                    account.getId(),
                    java.util.List.of(ROLE_USER, ROLE_ADMIN));
        } catch (DataAccessException ex) {
            if (userRoleService.countActiveAdminAccounts() > 0) {
                return;
            }
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Bootstrap admin account was created but role assignment failed", ex);
        }
    }

    private static String safeField(String value) {
        return value == null ? "<missing>" : (value.isBlank() ? "<blank>" : "<provided>");
    }
}
