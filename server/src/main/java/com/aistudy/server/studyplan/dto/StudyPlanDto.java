package com.aistudy.server.studyplan.dto;

import com.aistudy.server.studyplan.entity.StudyPlan;
import com.aistudy.server.studyplan.entity.StudyTask;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-016 — typed StudyPlan DTOs.
 *
 * <p>Generate request carries ONLY plan metadata + a bounded daily
 * limit — mastery/score values are never client-submitted (they are
 * derived server-side from review/mastery/diagnosis facts).
 */
public final class StudyPlanDto {

    private StudyPlanDto() {
    }

    /** Generate request: name + optional dates + bounded dailyItemLimit. */
    public record GenerateStudyPlanRequest(
            @NotBlank @Size(max = 255) String name,
            LocalDateTime startDate,
            LocalDateTime endDate,
            @Min(1) @Max(50) Integer dailyItemLimit
    ) {
    }

    /** One task view. */
    public record StudyTaskView(
            Long id,
            String taskType,
            String targetType,
            Long targetId,
            String title,
            String reason,
            LocalDateTime dueAt,
            String priority,
            String status,
            LocalDateTime completedAt,
            LocalDateTime createdAt
    ) {
        public static StudyTaskView from(StudyTask task) {
            return new StudyTaskView(
                    task.getId(), task.getTaskType(), task.getTargetType(),
                    task.getTargetId(), task.getTitle(), task.getReason(),
                    task.getDueAt(), task.getPriority(), task.getStatus(),
                    task.getCompletedAt(), task.getCreatedAt());
        }
    }

    /** Plan view: header + ordered tasks. */
    public record StudyPlanView(
            Long id,
            String name,
            LocalDateTime startDate,
            LocalDateTime endDate,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<StudyTaskView> tasks
    ) {
        public static StudyPlanView from(StudyPlan plan, List<StudyTask> tasks) {
            return new StudyPlanView(
                    plan.getId(), plan.getName(), plan.getStartDate(), plan.getEndDate(),
                    plan.getStatus(), plan.getCreatedAt(), plan.getUpdatedAt(),
                    tasks == null ? List.of() : tasks.stream().map(StudyTaskView::from).toList());
        }
    }
}
