package com.aistudy.server.exam.dto;

import java.time.LocalDate;
import java.util.Map;

public final class ExamStatisticsDto {

    private ExamStatisticsDto() {
    }

    public record SpaceOverview(
            Long spaceId,
            Long learningDurationMs,
            Double practiceAccuracy,
            Long wrongQuestionCount,
            Long reviewCompletionRate,
            Long examHistoryCount,
            Double avgExamScoreRatio,
            Map<String, Long> masteryDistribution
    ) {
    }

    public record TimeSeriesPoint(
            LocalDate bucket,
            Long durationMs,
            Long practiceCount,
            Long correctCount,
            Long wrongCount,
            Long examCount,
            Long reviewCompleted,
            Long studyTaskCompleted
    ) {
    }

    public record KpPracticeTrend(
            Long knowledgePointId,
            String kpTitle,
            Long totalAttempts,
            Long correctCount,
            Double accuracy
    ) {
    }

    public record ExamTrend(
            LocalDate bucket,
            Long attemptCount,
            Double avgScore,
            Double avgMaxScore
    ) {
    }

    public record ReviewBacklogTrend(
            LocalDate bucket,
            Long pendingCount,
            Long completedCount
    ) {
    }

    public record MasteryMovement(
            String bucket,
            Long count
    ) {
    }

    public record StudyTaskCompletion(
            String bucket,
            Long completedCount,
            Long totalCount
    ) {
    }

    public record QuestionTypePerformance(
            String questionType,
            Long totalAnswers,
            Long correctCount,
            Double accuracy
    ) {
    }

    public record GlobalSpaceOverview(
            Long spaceId,
            String spaceName,
            Long memberCount,
            Long examCount,
            Long masteryCount,
            Double avgMasteryScore
    ) {
    }
}
