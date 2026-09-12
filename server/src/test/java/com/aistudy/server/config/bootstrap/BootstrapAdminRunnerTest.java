package com.aistudy.server.config.bootstrap;

import com.aistudy.server.config.properties.BootstrapAdminProperties;
import com.aistudy.server.auth.entity.UserAccount;
import com.aistudy.server.auth.mapper.UserAccountMapper;
import com.aistudy.server.auth.service.UserAccountService;
import com.aistudy.server.auth.service.UserRoleService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS-026 — first-admin bootstrap runner unit test.
 */
class BootstrapAdminRunnerTest {

    @Test
    void disabledByDefaultDoesNothing() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setEnabled(false);

        UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);
        when(userAccountMapper.selectBySubject(any())).thenReturn(new UserAccount());
        when(userAccountMapper.selectByUsername(any())).thenReturn(new UserAccount());

        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                properties,
                mock(UserAccountService.class),
                mock(UserRoleService.class),
                userAccountMapper
        );

        runner.run(mock(ApplicationArguments.class));

        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void enabledAndMissingFieldsFailsStartup() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setEnabled(true);
        properties.setUsername("");
        properties.setPassword(null);

        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                properties,
                mock(UserAccountService.class),
                mock(UserRoleService.class),
                mock(UserAccountMapper.class)
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runner.run(mock(ApplicationArguments.class)));
        assertTrue(exception.getMessage().contains("required"));
    }

    @Test
    void firstBootstrapAccountGetsUserAndAdminRoles() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setEnabled(true);
        properties.setUsername("bootstrap-admin");
        properties.setPassword("bootstrap-P***word");

        UserAccountService userAccountService = mock(UserAccountService.class);
        UserRoleService userRoleService = mock(UserRoleService.class);
        UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);

        UserAccount account = new UserAccount();
        account.setId(7L);
        account.setSubject("bootstrap-subject");
        account.setUsername("bootstrap-admin");

        when(userAccountService.createAccount(eq("bootstrap-admin"), eq("bootstrap-P***word")))
                .thenReturn("bootstrap-subject");
        when(userAccountMapper.selectBySubject("bootstrap-subject")).thenReturn(account);
        when(userRoleService.countActiveAdminAccounts()).thenReturn(0L);

        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                properties,
                userAccountService,
                userRoleService,
                userAccountMapper
        );

        runner.run(mock(ApplicationArguments.class));

        verify(userRoleService).replaceRoles(eq(7L), eq(List.of("USER", "ADMIN")));
    }

    @Test
    void existingAdminIsNotPasswordReset() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setEnabled(true);
        properties.setUsername("second-admin");
        properties.setPassword("new-secret");

        UserAccountService userAccountService = mock(UserAccountService.class);
        UserRoleService userRoleService = mock(UserRoleService.class);
        UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);

        when(userRoleService.countActiveAdminAccounts()).thenReturn(1L);

        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                properties,
                userAccountService,
                userRoleService,
                userAccountMapper
        );

        runner.run(mock(ApplicationArguments.class));

        verify(userAccountService, never()).createAccount(any(), any());
    }

    @Test
    void repeatedBootstrapIsSafe() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setEnabled(true);
        properties.setUsername("repeat-admin");
        properties.setPassword("repeat-P***word");

        UserAccountService userAccountService = mock(UserAccountService.class);
        UserRoleService userRoleService = mock(UserRoleService.class);
        UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);

        when(userRoleService.countActiveAdminAccounts()).thenReturn(1L);

        BootstrapAdminRunner runner = new BootstrapAdminRunner(
                properties,
                userAccountService,
                userRoleService,
                userAccountMapper
        );

        runner.run(mock(ApplicationArguments.class));
        runner.run(mock(ApplicationArguments.class));

        verify(userAccountService, never()).createAccount(any(), any());
    }
}
