package com.aistudy.server.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * §9.8 — SystemConfig persistence model.
 *
 * <p>Typed whitelist registry for DB-backed mutable settings.
 * Three categories (data-model §28):
 * <ul>
 *   <li>READ_ONLY_EFFECTIVE — only changeable by restart/config file;</li>
 *   <li>RUNTIME_MODIFIABLE — safe to change at runtime via API;</li>
 *   <li>NEVER_READABLE — secrets (DB password, JWT secret, API key,
 *       encryption master key); stored encrypted, returned as
 *       {@code ***REDACTED***} in all API responses.</li>
 * </ul>
 */
@TableName("system_config")
public class SystemConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;

    private String configValue;

    private String configType;

    private Integer version;

    private String validationSchema;

    private Boolean restartRequired;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigType() {
        return configType;
    }

    public void setConfigType(String configType) {
        this.configType = configType;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getValidationSchema() {
        return validationSchema;
    }

    public void setValidationSchema(String validationSchema) {
        this.validationSchema = validationSchema;
    }

    public Boolean getRestartRequired() {
        return restartRequired;
    }

    public void setRestartRequired(Boolean restartRequired) {
        this.restartRequired = restartRequired;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
