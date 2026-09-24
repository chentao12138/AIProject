package com.aistudy.server.space.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * BUSINESS-001 — production LearningSpace persistence model.
 *
 * <p>Backs the {@code learning_space} table (V004 migration). This is
 * the first-class isolation boundary of the product: every learning
 * entity will reference a LearningSpace, and every read/write in the
 * system is scoped by it (docs/data-model.md §4.1,
 * docs/business-baseline.md §2.1).
 *
 * <h3>Owner model (v1)</h3>
 *
 * A LearningSpace has exactly one owner, identified by the JWT
 * {@code sub} claim ({@code ownerSubject}). There is no formal User
 * table and no FK to one — the subject string is the identity key
 * until a real User entity exists. Multi-owner collaboration is a
 * future ADR; the {@code spike_space_membership} SPIKE table is NOT
 * reused for production membership.
 *
 * <h3>MyBatis-Plus mapping</h3>
 *
 * <ul>
 *   <li>{@code @TableName("learning_space")} — explicit table name,
 *       following the {@code SpikeRecord} SPIKE-002 convention.</li>
 *   <li>{@code @TableId(type = IdType.AUTO)} — BIGINT AUTO_INCREMENT
 *       primary key (matches the V003 SPIKE table style).</li>
 *   <li>{@code ownerSubject} / {@code createdAt} / {@code updatedAt}
 *       map to {@code owner_subject} / {@code created_at} /
 *       {@code updated_at} via the global
 *       {@code map-underscore-to-camel-case: true} setting in
 *       application.yml.</li>
 * </ul>
 *
 * <h3>Why a mutable POJO and not a record</h3>
 *
 * <p>MyBatis-Plus instantiates entities reflectively and mutates them
 * through setters during result mapping and ID back-fill after
 * insert; a Java record (immutable by design) is not the right shape
 * for the persistence layer. The API-facing records live in
 * {@code com.aistudy.server.space.dto} and are converted from this
 * entity by the controller.
 *
 * <p>This class carries no business rules — it is a plain persistence
 * holder. Rules (owner binding, status defaulting, timestamp
 * maintenance) live in
 * {@link com.aistudy.server.space.service.LearningSpaceService}.
 */
@TableName("learning_space")
public class LearningSpace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private String ownerSubject;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime archivedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public void setOwnerSubject(String ownerSubject) {
        this.ownerSubject = ownerSubject;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    @Override
    public String toString() {
        return "LearningSpace{id=" + id
                + ", name='" + name + '\''
                + ", ownerSubject='" + ownerSubject + '\''
                + ", status='" + status + '\''
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + '}';
    }
}
