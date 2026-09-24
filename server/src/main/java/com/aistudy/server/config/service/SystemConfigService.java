package com.aistudy.server.config.service;

import com.aistudy.server.config.entity.SystemConfig;
import com.aistudy.server.config.mapper.SystemConfigMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SystemConfig — server-owned typed whitelist registry.
 *
 * <p>Each key's type / validation / restartRequired is FIXED in code.
 * Clients may only supply {@code value}; they cannot mark secrets,
 * invent types, or edit JWT/DB/AI master keys here.
 */
@Service
public class SystemConfigService {

    public enum ConfigType {
        RUNTIME_MODIFIABLE,
        READ_ONLY_EFFECTIVE
    }

    /** Immutable server descriptor for one whitelist key. */
    public record ConfigDescriptor(
            String key,
            ConfigType type,
            String valueType, // number | boolean | string
            Double min,
            Double max,
            boolean restartRequired,
            String description
    ) {
    }

    private static final Map<String, ConfigDescriptor> REGISTRY = new LinkedHashMap<>();

    static {
        REGISTRY.put("ai.temperature", new ConfigDescriptor(
                "ai.temperature", ConfigType.RUNTIME_MODIFIABLE, "number", 0.0, 2.0, false,
                "Default AI temperature for generation calls without explicit override"));
        REGISTRY.put("ai.maxOutputTokens", new ConfigDescriptor(
                "ai.maxOutputTokens", ConfigType.RUNTIME_MODIFIABLE, "number", 16.0, 8192.0, false,
                "Default max output tokens for AI generation"));
        REGISTRY.put("studyPlan.dailyItemLimit", new ConfigDescriptor(
                "studyPlan.dailyItemLimit", ConfigType.RUNTIME_MODIFIABLE, "number", 1.0, 50.0, false,
                "Max study-plan tasks generated per day"));
        REGISTRY.put("mastery.confidenceFullSamples", new ConfigDescriptor(
                "mastery.confidenceFullSamples", ConfigType.RUNTIME_MODIFIABLE, "number", 1.0, 100.0, false,
                "Evidence sample count for full mastery confidence"));
    }

    private final SystemConfigMapper systemConfigMapper;

    public SystemConfigService(SystemConfigMapper systemConfigMapper) {
        this.systemConfigMapper = systemConfigMapper;
    }

    public Map<String, ConfigDescriptor> registry() {
        return REGISTRY;
    }

    public SystemConfig get(String key) {
        return systemConfigMapper.selectByKey(key);
    }

    /** Reads a numeric runtime key; falls back to defaultValue when absent/invalid. */
    public double getNumber(String key, double defaultValue) {
        ConfigDescriptor d = requireDescriptor(key);
        SystemConfig config = systemConfigMapper.selectByKey(key);
        if (config == null || config.getConfigValue() == null) {
            return defaultValue;
        }
        try {
            double v = Double.parseDouble(config.getConfigValue());
            validateRange(d, v);
            return v;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Create or update a RUNTIME key using the server descriptor.
     * Clients never supply type/schema/restartRequired.
     */
    @Transactional
    public SystemConfig upsertValue(String key, String value) {
        ConfigDescriptor d = requireDescriptor(key);
        if (d.type() != ConfigType.RUNTIME_MODIFIABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Key is not runtime-modifiable: " + key);
        }
        validateValue(d, value);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        SystemConfig existing = systemConfigMapper.selectByKey(key);
        if (existing == null) {
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setConfigType(d.type().name());
            config.setVersion(1);
            config.setValidationSchema(null);
            config.setRestartRequired(d.restartRequired());
            config.setCreatedAt(now);
            config.setUpdatedAt(now);
            systemConfigMapper.insert(config);
            return config;
        }
        int nextVersion = (existing.getVersion() == null ? 0 : existing.getVersion()) + 1;
        existing.setConfigValue(value);
        existing.setConfigType(d.type().name());
        existing.setRestartRequired(d.restartRequired());
        existing.setVersion(nextVersion);
        existing.setUpdatedAt(now);
        systemConfigMapper.updateById(existing);
        return existing;
    }

    private ConfigDescriptor requireDescriptor(String key) {
        ConfigDescriptor d = REGISTRY.get(key);
        if (d == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Key is not in the whitelist: " + key);
        }
        return d;
    }

    private static void validateValue(ConfigDescriptor d, String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must not be blank");
        }
        if ("number".equals(d.valueType())) {
            double v;
            try {
                v = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "value must be a number for key " + d.key());
            }
            validateRange(d, v);
        } else if ("boolean".equals(d.valueType())) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "value must be true/false for key " + d.key());
            }
        }
    }

    private static void validateRange(ConfigDescriptor d, double v) {
        if (d.min() != null && v < d.min()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "value below minimum for key " + d.key());
        }
        if (d.max() != null && v > d.max()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "value above maximum for key " + d.key());
        }
    }
}
