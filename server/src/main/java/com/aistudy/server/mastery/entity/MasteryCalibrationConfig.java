package com.aistudy.server.mastery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * §7.3 — MasteryCalibrationConfig persistence model.
 *
 * <p>Versioned weight/confidence policy for mastery recompute.
 * At most one active version at a time (service-enforced).
 * Admin dry-run/recompute endpoints use the active version.
 */
@TableName("mastery_calibration_config")
public class MasteryCalibrationConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String version;

    private Integer confidenceFullSamples;

    private Integer recencyDaysCap;

    private Integer volumeCap;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Integer getConfidenceFullSamples() {
        return confidenceFullSamples;
    }

    public void setConfidenceFullSamples(Integer confidenceFullSamples) {
        this.confidenceFullSamples = confidenceFullSamples;
    }

    public Integer getRecencyDaysCap() {
        return recencyDaysCap;
    }

    public void setRecencyDaysCap(Integer recencyDaysCap) {
        this.recencyDaysCap = recencyDaysCap;
    }

    public Integer getVolumeCap() {
        return volumeCap;
    }

    public void setVolumeCap(Integer volumeCap) {
        this.volumeCap = volumeCap;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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
