package com.aistudy.server.auth.controller;

import com.aistudy.server.auth.dto.AdminUserDetail;
import com.aistudy.server.auth.dto.AdminUserPageResponse;
import com.aistudy.server.auth.dto.AdminUserSummary;
import com.aistudy.server.auth.dto.CreateUserRequest;
import com.aistudy.server.auth.dto.ResetPasswordRequest;
import com.aistudy.server.auth.dto.UpdateUserRolesRequest;
import com.aistudy.server.auth.dto.UpdateUserStatusRequest;
import com.aistudy.server.auth.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public AdminUserPageResponse listUsers(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return adminUserService.listUsers(page, Math.min(size, 100));
    }

    @GetMapping("/{subject}")
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<AdminUserDetail> getUser(@PathVariable String subject) {
        AdminUserDetail detail = adminUserService.getUserDetail(subject);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserSummary createUser(@Valid @RequestBody CreateUserRequest request) {
        return adminUserService.createUser(request);
    }

    @PatchMapping("/{subject}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> updateStatus(@PathVariable String subject,
                                             @Valid @RequestBody UpdateUserStatusRequest request) {
        adminUserService.updateStatus(subject, request.status().name());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{subject}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> updateRoles(@PathVariable String subject,
                                            @Valid @RequestBody UpdateUserRolesRequest request) {
        adminUserService.updateRoles(subject, request.roles());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{subject}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> resetPassword(@PathVariable String subject,
                                              @Valid @RequestBody ResetPasswordRequest request) {
        adminUserService.resetPassword(subject, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
