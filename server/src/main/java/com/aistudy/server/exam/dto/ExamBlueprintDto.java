package com.aistudy.server.exam.dto;

import com.aistudy.server.exam.entity.ExamBlueprint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class ExamBlueprintDto {

    private ExamBlueprintDto() {
    }

    public record CreateExamBlueprintRequest(
            @NotBlank(message = "title must not be blank")
            @Size(max = 255, message = "title must be at most 255 characters")
            String title,

            @NotNull(message = "rules must not be null")
            BlueprintRules rules
    ) {
    }

    public record BlueprintRules(
            @Size(max = 16, message = "at most 16 question type distribution entries")
            java.util.Map<String, Integer> questionTypeDistribution,

            @Size(max = 16, message = "at most 16 difficulty distribution entries")
            java.util.Map<String, Integer> difficultyDistribution,

            @Size(max = 100, message = "at most 100 category scope entries")
            List<Long> categoryScope,

            @Size(max = 500, message = "at most 500 KP scope entries")
            List<Long> kpScope,

            @Size(max = 100, message = "at most 100 exclude question ids")
            List<Long> excludeQuestionIds,

            @Size(max = 100, message = "at most 100 include question ids")
            List<Long> includeQuestionIds,

            Integer totalQuestionCount,

            Integer timeLimitMinutes
    ) {
    }

    public record ExamBlueprintResponse(
            Long id,
            Long spaceId,
            String title,
            String rulesJson,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static ExamBlueprintResponse from(ExamBlueprint bp) {
            return new ExamBlueprintResponse(
                    bp.getId(), bp.getSpaceId(), bp.getTitle(),
                    bp.getRulesJson(), bp.getStatus(),
                    bp.getCreatedAt(), bp.getUpdatedAt());
        }
    }

    public record ExamBlueprintGenerateResponse(
            Long blueprintId,
            Long examId,
            Long examPaperId,
            Integer paperVersion,
            Integer totalQuestions
    ) {
    }
}
