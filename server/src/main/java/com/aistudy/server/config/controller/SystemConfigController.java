package com.aistudy.server.config.controller;

import com.aistudy.server.config.entity.SystemConfig;
import com.aistudy.server.config.service.SystemConfigService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * ADMIN SystemConfig API.
 *
 * <p>Clients may only read the registry and set {@code value} on
 * RUNTIME keys. Type/validation/restartRequired are server-owned.
 * This is NOT a secret/env editor.
 */
@RestController
@RequestMapping("/api/v1/admin/system-config")
@SecurityRequirement(name = "bearerAuth")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    public record UpsertValueRequest(@NotBlank String value) {
    }

    public record DescriptorView(
            String key,
            String type,
            String valueType,
            Double min,
            Double max,
            boolean restartRequired,
            String description,
            String currentValue,
            Integer version
    ) {
    }

    @GetMapping("/registry")
    @PreAuthorize("hasRole('ADMIN')")
    public List<DescriptorView> registry() {
        List<DescriptorView> out = new ArrayList<>();
        for (SystemConfigService.ConfigDescriptor d : systemConfigService.registry().values()) {
            SystemConfig current = systemConfigService.get(d.key());
            out.add(new DescriptorView(
                    d.key(), d.type().name(), d.valueType(), d.min(), d.max(),
                    d.restartRequired(), d.description(),
                    current == null ? null : current.getConfigValue(),
                    current == null ? null : current.getVersion()));
        }
        return out;
    }

    @GetMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public DescriptorView get(@PathVariable String key) {
        SystemConfigService.ConfigDescriptor d = systemConfigService.registry().get(key);
        if (d == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "SystemConfig key not found");
        }
        SystemConfig current = systemConfigService.get(key);
        return new DescriptorView(
                d.key(), d.type().name(), d.valueType(), d.min(), d.max(),
                d.restartRequired(), d.description(),
                current == null ? null : current.getConfigValue(),
                current == null ? null : current.getVersion());
    }

    @PutMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public DescriptorView upsert(@PathVariable String key,
                                 @Valid @RequestBody UpsertValueRequest request) {
        SystemConfig saved = systemConfigService.upsertValue(key, request.value());
        SystemConfigService.ConfigDescriptor d = systemConfigService.registry().get(key);
        return new DescriptorView(
                d.key(), d.type().name(), d.valueType(), d.min(), d.max(),
                d.restartRequired(), d.description(),
                saved.getConfigValue(), saved.getVersion());
    }
}
