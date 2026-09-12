package com.aistudy.server.auth.controller;

import com.aistudy.server.auth.dto.AdminUserDetail;
import com.aistudy.server.auth.dto.AdminUserPageResponse;
import com.aistudy.server.auth.dto.AdminUserSummary;
import com.aistudy.server.auth.dto.UpdateUserRolesRequest;
import com.aistudy.server.auth.dto.UpdateUserStatusRequest;
import com.aistudy.server.auth.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.util.List;

/**
 * BUSINESS-019 — minimal platform admin API.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public AdminUserPageResponse listUsers(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                           @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return adminUserService.listUsers(page, Math.min(size, 100));
    }

    @GetMapping("/{subject}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<AdminUserDetail> getUser(@PathVariable String subject) {
        AdminUserDetail detail = adminUserService.getUserDetail(subject);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    @PatchMapping("/{subject}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> updateStatus(@PathVariable String subject,
                                             @Valid @RequestBody UpdateUserStatusRequest request) {
        adminUserService.updateStatus(subject, request.status().name());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{subject}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> updateRoles(@PathVariable String subject,
                                            @Valid @RequestBody UpdateUserRolesRequest request) {
        adminUserService.updateRoles(subject, request.roles());
        return ResponseEntity.noContent().build();
    }
}
