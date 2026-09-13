package com.aistudy.server.auth.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

/**
 * BUSINESS-018 — refresh session domain entity.
 *
 * <p>The plaintext refresh token is never stored. {@link #tokenHash}
 * stores the deterministic SHA-256 hex digest, and {@link #familyId}
 * groups tokens produced by rotation.
 */
@TableName("auth_refresh_session")
public class RefreshSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userAccountId;
    private Long familyId;
    private String tokenHash;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime rotatedAt;
    private LocalDateTime revokedAt;
    private Long replacedById;
    private String revokeReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public Long getUserAccountId() {
        return userAccountId;
    }

    public Long getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getRotatedAt() {
        return rotatedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public Long getReplacedById() {
        return replacedById;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setUserAccountId(Long userAccountId) {
        this.userAccountId = userAccountId;
    }

    public void setFamilyId(Long familyId) {
        this.familyId = familyId;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public void setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public void setRotatedAt(LocalDateTime rotatedAt) {
        this.rotatedAt = rotatedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public void setReplacedById(Long replacedById) {
        this.replacedById = replacedById;
    }

    public void setRevokeReason(String revokeReason) {
        this.revokeReason = revokeReason;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
