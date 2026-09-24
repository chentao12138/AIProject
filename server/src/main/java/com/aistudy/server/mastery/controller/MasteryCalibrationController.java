package com.aistudy.server.mastery.controller;

import com.aistudy.server.mastery.entity.MasteryCalibrationConfig;
import com.aistudy.server.mastery.service.MasteryCalibrationConfigService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/mastery/calibration")
@SecurityRequirement(name = "bearerAuth")
public class MasteryCalibrationController {

    private final MasteryCalibrationConfigService calibrationService;

    public MasteryCalibrationController(MasteryCalibrationConfigService calibrationService) {
        this.calibrationService = calibrationService;
    }

    public record CreateCalibrationRequest(
            @NotBlank String version,
            Integer confidenceFullSamples,
            Integer recencyDaysCap,
            Integer volumeCap
    ) {
    }

    public record CalibrationView(
            Long id,
            String version,
            Integer confidenceFullSamples,
            Integer recencyDaysCap,
            Integer volumeCap,
            boolean active
    ) {
        public static CalibrationView from(MasteryCalibrationConfig config) {
            return new CalibrationView(
                    config.getId(),
                    config.getVersion(),
                    config.getConfidenceFullSamples(),
                    config.getRecencyDaysCap(),
                    config.getVolumeCap(),
                    Boolean.TRUE.equals(config.getActive()));
        }
    }

    public record RecomputeResultView(
            int processed,
            int failed,
            List<String> errors
    ) {
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<CalibrationView> list() {
        return calibrationService.list().stream()
                .map(CalibrationView::from)
                .toList();
    }

    @GetMapping("/active")
    @PreAuthorize("hasRole('ADMIN')")
    public CalibrationView getActive() {
        MasteryCalibrationConfig active = calibrationService.getActive();
        if (active == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No active calibration version");
        }
        return CalibrationView.from(active);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public CalibrationView create(@RequestBody CreateCalibrationRequest request) {
        MasteryCalibrationConfig config = calibrationService.create(
                request.version(),
                request.confidenceFullSamples(),
                request.recencyDaysCap(),
                request.volumeCap());
        return CalibrationView.from(config);
    }

    @PostMapping("/{version}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public CalibrationView activate(@PathVariable String version) {
        return CalibrationView.from(calibrationService.activate(version));
    }

    @PostMapping("/recompute")
    @PreAuthorize("hasRole('ADMIN')")
    public RecomputeResultView recompute(@RequestParam String userSubject,
                                         @RequestParam Long spaceId) {
        var result = calibrationService.recomputeAll(userSubject, spaceId);
        return new RecomputeResultView(
                result.processed(), result.failed(), result.errors());
    }
}
