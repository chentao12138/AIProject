package com.aistudy.server.mastery.dto;

import com.aistudy.server.mastery.entity.Mastery;

import java.time.LocalDateTime;

/**
 * BUSINESS-011 — mastery view. The three evidence counts are flat members on
 * purpose: data-model.md §15.1 and learning-engine.md list them as fields of
 * Mastery itself, and the contract tests read them at the top level.
 */
public record MasteryResponse(
        Long id,
        Long knowledgePointId,
        Double masteryScore,
        Double confidence,
        String algorithmVersion,
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
                mastery.getAlgorithmVersion(),
                mastery.getPracticeEvidenceCount(),
                mastery.getExamEvidenceCount(),
                mastery.getReviewEvidenceCount(),
                mastery.getLastEvidenceAt(),
                mastery.getUpdatedAt());
    }
}
