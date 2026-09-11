package com.aistudy.server.mastery.dto;

import com.aistudy.server.mastery.entity.Mastery;

import java.time.LocalDateTime;

/**
 * BUSINESS-014 — typed mastery response.
 *
 * <p>Score/confidence are always accompanied by their evidence
 * counts so every value is explainable (data-model.md §15).
 */
public record MasteryResponse(
        Long id,
        Long knowledgePointId,
        Double masteryScore,
        Double confidence,
        Integer practiceEvidenceCount,
        Integer examEvidenceCount,
        Integer reviewEvidenceCount,
        LocalDateTime lastEvidenceAt,
        LocalDateTime updatedAt
) {
    public static MasteryResponse from(Mastery mastery) {
        return new MasteryResponse(
                mastery.getId(),
                mastery.getKnowledgePointId(),
                mastery.getMasteryScore(),
                mastery.getConfidence(),
                mastery.getPracticeEvidenceCount(),
                mastery.getExamEvidenceCount(),
                mastery.getReviewEvidenceCount(),
                mastery.getLastEvidenceAt(),
                mastery.getUpdatedAt());
    }
}
