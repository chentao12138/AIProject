package com.aistudy.server.auth.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

/**
 * BUSINESS-019 — user_account_role domain entity.
 */
@TableName("user_account_role")
public class UserAccountRole {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userAccountId;
    private String role;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Long getUserAccountId() {
        return userAccountId;
    }

    public String getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setUserAccountId(Long userAccountId) {
        this.userAccountId = userAccountId;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
