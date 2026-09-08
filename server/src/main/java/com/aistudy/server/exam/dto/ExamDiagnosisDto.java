package com.aistudy.server.exam.dto;

import com.aistudy.server.exam.entity.ExamDiagnosis;
import com.aistudy.server.exam.entity.ExamDiagnosisItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-015 — typed ExamDiagnosis DTOs.
 *
 * <p>Aggregated, explainable numbers only: score / maxScore /
 * accuracy / evidenceCount per dimension. NEVER exposes individual
 * answers, correct answers, or answer payloads (api-guidelines.md
 * §9 spirit: only what the result/diagnosis intentionally reveals).
 */
public final class ExamDiagnosisDto {

    private ExamDiagnosisDto() {
    }

    /** One dimension aggregate of a diagnosis. */
    public record ItemView(
            Long id,
            String dimensionType,
            Long dimensionId,
            String label,
            Integer score,
            Integer maxScore,
            Double accuracy,
            Integer evidenceCount,
            String severity,
            String recommendation
    ) {
        public static ItemView from(ExamDiagnosisItem item) {
            return new ItemView(
                    item.getId(), item.getDimensionType(), item.getDimensionId(),
                    item.getLabel(), item.getScore(), item.getMaxScore(),
                    item.getAccuracy(), item.getEvidenceCount(),
                    item.getSeverity(), item.getRecommendation());
        }
    }

    /** Full diagnosis: header + ordered dimension items. */
    public record ExamDiagnosisView(
            Long id,
            Long examAttemptId,
            String summary,
            LocalDateTime createdAt,
            List<ItemView> items
    ) {
        public static ExamDiagnosisView from(ExamDiagnosis diagnosis,
                                             List<ExamDiagnosisItem> items) {
            return new ExamDiagnosisView(
                    diagnosis.getId(), diagnosis.getExamAttemptId(),
                    diagnosis.getSummary(), diagnosis.getCreatedAt(),
                    items == null ? List.of() : items.stream().map(ItemView::from).toList());
        }
    }
}
