package com.aistudy.server.ai.learning;

import java.util.List;

/**
 * AI-006 — bounded read-only learning-state snapshot for tutor/coach prompts.
 * Never a raw DB dump.
 */
public record AiLearningState(
        List<WeakKnowledgePoint> weakKnowledgePoints,
        List<WrongSignal> wrongSignals,
        List<DiagnosisWeakness> diagnosisWeaknesses,
        List<NextAction> nextActions,
        boolean hasActivePlan
) {

    public static AiLearningState empty() {
        return new AiLearningState(List.of(), List.of(), List.of(), List.of(), false);
    }

    public record WeakKnowledgePoint(
            Long knowledgePointId,
            String title,
            Double masteryScore,
            Double confidence
    ) {
    }

    public record WrongSignal(
            Long questionId,
            Integer wrongCount,
            String status
    ) {
    }

    public record DiagnosisWeakness(
            Long knowledgePointId,
            String label,
            String severity,
            String recommendation
    ) {
    }

    public record NextAction(
            Long taskId,
            String taskType,
            String targetType,
            Long targetId,
            String title,
            String status
    ) {
    }
}
