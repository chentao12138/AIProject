package com.aistudy.server.auth.entity;

/**
 * BUSINESS-017 — user_account domain entity.
 *
 * <p>This class intentionally does NOT carry authentication authorities
 * or roles. The production auth foundation only models identity + password
 * hash; ADMIN/RBAC is deferred to BUSINESS-019.
 */
public class UserAccount {

    private Long id;
    private String subject;
    private String username;
    private String passwordHash;
    private String status;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getStatus() {
        return status;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
