package com.aistudy.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BUSINESS-026 bootstrap administrator properties.
 *
 * <p>These properties are intentionally not persisted after a
 * successful bootstrap. Treat them as one-time provisioning
 * credentials.
 */
@ConfigurationProperties(prefix = "aistudy.bootstrap.admin")
public class BootstrapAdminProperties {

    /**
     * Enable first-admin bootstrap.
     *
     * <p>Defaults to {@code false}. Production deployments should
     * only set this to {@code true} for the first start when no
     * ADMIN exists.
     */
    private boolean enabled = false;

    /**
     * Bootstrap admin username.
     *
     * <p>Required when {@link #enabled} is {@code true}.
     */
    private String username;

    /**
     * Bootstrap admin raw password.
     *
     * <p>Required when {@link #enabled} is {@code true}.
     *
     * <p>WARNING: this property carries a plaintext password only
     * during bootstrap. It must not be logged or stored after the
     * runner creates the account.
     */
    private String password;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
