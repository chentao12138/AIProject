package com.aistudy.server.mastery.dto;

import com.aistudy.server.mastery.entity.Mastery;

import java.time.LocalDateTime;

public record MasteryResponse(
        Long id,
        Long knowledgePointId,
        Double masteryScore,
        Double confidence,
        String algorithmVersion,
        EvidenceBreakdown evidence,
        LocalDateTime lastEvidenceAt,
        LocalDateTime updatedAt
) {
    public record EvidenceBreakdown(
            Integer practiceEvidenceCount,
            Integer examEvidenceCount,
            Integer reviewEvidenceCount
    ) {
    }

    public static MasteryResponse from(Mastery mastery) {
        return new MasteryResponse(
                mastery.getId(),
                mastery.getKnowledgePointId(),
                mastery.getMasteryScore(),
                mastery.getConfidence(),
                mastery.getAlgorithmVersion(),
                new EvidenceBreakdown(
                        mastery.getPracticeEvidenceCount(),
                        mastery.getExamEvidenceCount(),
                        mastery.getReviewEvidenceCount()
                ),
                mastery.getLastEvidenceAt(),
                mastery.getUpdatedAt());
    }
}
