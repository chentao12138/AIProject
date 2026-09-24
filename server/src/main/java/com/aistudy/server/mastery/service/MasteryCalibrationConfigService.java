package com.aistudy.server.mastery.service;

import com.aistudy.server.mastery.entity.Mastery;
import com.aistudy.server.mastery.entity.MasteryCalibrationConfig;
import com.aistudy.server.mastery.mapper.MasteryCalibrationConfigMapper;
import com.aistudy.server.mastery.mapper.MasteryMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * §7.3 — MasteryCalibrationConfig application service.
 *
 * <p>Versioned weight/confidence policy. Admin dry-run/recompute
 * job reads the active version and recomputes all mastery rows.
 */
@Service
public class MasteryCalibrationConfigService {

    private final MasteryCalibrationConfigMapper calibrationConfigMapper;
    private final MasteryMapper masteryMapper;
    private final MasteryService masteryService;

    public MasteryCalibrationConfigService(MasteryCalibrationConfigMapper calibrationConfigMapper,
                                           MasteryMapper masteryMapper,
                                           MasteryService masteryService) {
        this.calibrationConfigMapper = calibrationConfigMapper;
        this.masteryMapper = masteryMapper;
        this.masteryService = masteryService;
    }

    public List<MasteryCalibrationConfig> list() {
        return calibrationConfigMapper.selectList(null);
    }

    public MasteryCalibrationConfig getActive() {
        return calibrationConfigMapper.selectActive();
    }

    @Transactional
    public MasteryCalibrationConfig create(String version, Integer confidenceFullSamples,
                                          Integer recencyDaysCap, Integer volumeCap) {
        if (calibrationConfigMapper.selectByVersion(version) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "calibration version already exists: " + version);
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        MasteryCalibrationConfig config = new MasteryCalibrationConfig();
        config.setVersion(version);
        config.setConfidenceFullSamples(confidenceFullSamples);
        config.setRecencyDaysCap(recencyDaysCap);
        config.setVolumeCap(volumeCap);
        config.setActive(false);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        calibrationConfigMapper.insert(config);
        return config;
    }

    @Transactional
    public MasteryCalibrationConfig activate(String version) {
        MasteryCalibrationConfig target = calibrationConfigMapper.selectByVersion(version);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "calibration version not found: " + version);
        }
        List<MasteryCalibrationConfig> all = calibrationConfigMapper.selectList(null);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (MasteryCalibrationConfig c : all) {
            boolean shouldBeActive = c.getVersion().equals(version);
            if (Boolean.TRUE.equals(c.getActive()) != shouldBeActive) {
                c.setActive(shouldBeActive);
                c.setUpdatedAt(now);
                calibrationConfigMapper.updateById(c);
            }
        }
        target.setActive(true);
        target.setUpdatedAt(now);
        return target;
    }

    @Transactional
    public RecomputeResult recomputeAll(String ownerSubject, Long spaceId) {
        List<Mastery> rows = masteryMapper.selectBySpaceOwnerUser(spaceId, ownerSubject, ownerSubject);
        List<Long> kpIds = new ArrayList<>();
        for (Mastery m : rows) {
            kpIds.add(m.getKnowledgePointId());
        }
        int processed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (Long kpId : kpIds) {
            try {
                masteryService.recompute(ownerSubject, spaceId, kpId);
                processed++;
            } catch (Exception e) {
                failed++;
                errors.add("kp=" + kpId + ": " + e.getMessage());
            }
        }
        return new RecomputeResult(processed, failed, errors);
    }

    public record RecomputeResult(int processed, int failed, List<String> errors) {
    }
}
