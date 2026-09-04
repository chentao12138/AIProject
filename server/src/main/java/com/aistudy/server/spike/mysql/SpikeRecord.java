package com.aistudy.server.spike.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * SPIKE-002 ONLY.
 *
 * This entity verifies MyBatis-Plus + MySQL connectivity and CRUD.
 * It is NOT a business entity. Do not treat it as part of the formal data model.
 * Will be removed once SPIKE-002 passes.
 */
@TableName("spike_record")
public class SpikeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "SpikeRecord{id=" + id + ", name='" + name + "', description='" + description
                + "', createdAt=" + createdAt + "}";
    }
}
