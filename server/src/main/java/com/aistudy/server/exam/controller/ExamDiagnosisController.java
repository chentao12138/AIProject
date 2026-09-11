package com.aistudy.server.exam.controller;

import com.aistudy.server.exam.dto.ExamDiagnosisDto.ExamDiagnosisView;
import com.aistudy.server.exam.service.ExamDiagnosisService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BUSINESS-015 — ExamDiagnosis REST API (read-only; diagnosis is
 * generated deterministically at submit, never client-submitted).
 *
 * <pre>
 *   GET /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/diagnosis
 *       → 200 typed diagnosis; 404 (attempt absent/not owner/wrong
 *         space); 409 (attempt not SUBMITTED, or submitted attempt
 *         with missing diagnosis)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
@SecurityRequirement(name = "bearerAuth")
public class ExamDiagnosisController {

    private final ExamDiagnosisService examDiagnosisService;

    public ExamDiagnosisController(ExamDiagnosisService examDiagnosisService) {
        this.examDiagnosisService = examDiagnosisService;
    }

    @GetMapping(value = "/exam-attempts/{attemptId}/diagnosis",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamDiagnosisView get(@PathVariable Long spaceId,
                                 @PathVariable Long attemptId,
                                 Authentication authentication) {
        return examDiagnosisService.getMine(authentication.getName(), spaceId, attemptId);
    }
}
