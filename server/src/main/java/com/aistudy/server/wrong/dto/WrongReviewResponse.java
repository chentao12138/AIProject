package com.aistudy.server.wrong.dto;

import com.aistudy.server.wrong.entity.ReviewTask;
import com.aistudy.server.wrong.entity.WrongQuestion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * BUSINESS-011 — typed responses for WrongQuestion / ReviewTask.
 */
public final class WrongReviewResponse {

    private WrongReviewResponse() {
    }

    /** Wrong-question list item. */
    public record WrongQuestionView(
            Long id,
            Long questionId,
            LocalDateTime firstWrongAt,
            LocalDateTime lastWrongAt,
            Integer wrongCount,
            LocalDateTime lastCorrectAt,
            String status,
            LocalDateTime dismissedAt
    ) {
        public static WrongQuestionView from(WrongQuestion wq) {
            return new WrongQuestionView(
                    wq.getId(), wq.getQuestionId(), wq.getFirstWrongAt(),
                    wq.getLastWrongAt(), wq.getWrongCount(),
                    wq.getLastCorrectAt(), wq.getStatus(),
                    wq.getDismissedAt());
        }
    }

    /** Review-task list item. */
    public record ReviewTaskView(
            Long id,
            String targetType,
            Long targetId,
            String reason,
            LocalDateTime dueAt,
            String priority,
            String status,
            LocalDateTime createdAt
    ) {
        public static ReviewTaskView from(ReviewTask task) {
            return new ReviewTaskView(
                    task.getId(), task.getTargetType(), task.getTargetId(),
                    task.getReason(), task.getDueAt(), task.getPriority(),
                    task.getStatus(), task.getCreatedAt());
        }
    }

    /** Request body for completing a review task. */
    public record CompleteReviewTaskRequest(
            @NotBlank(message = "result must not be blank")
            @Size(max = 16, message = "result must be CORRECT or WRONG")
            String result,

            Integer durationMs,

            @Size(max = 1000, message = "notes must be at most 1000 characters")
            String notes
    ) {
    }

    /** Request body for manually creating a review task. */
    public record CreateReviewTaskRequest(
            @NotBlank(message = "targetType must not be blank")
            @Size(max = 32, message = "targetType must be at most 32 characters")
            String targetType,

            @NotNull(message = "targetId must not be null")
            Long targetId,

            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime dueAt,

            @Size(max = 16, message = "priority must be at most 16 characters")
            String priority,

            @Size(max = 1000, message = "notes must be at most 1000 characters")
            String notes
    ) {
    }

    /** Completion response: task + wrong-question follow-up state. */
    public record CompleteReviewTaskView(
            Long taskId,
            String status,
            String result,
            String wrongQuestionStatus,
            LocalDateTime nextDueAt,
            LocalDateTime completedAt
    ) {
    }

    /** Response after manually creating a review task. */
    public record CreateReviewTaskView(
            Long id,
            String targetType,
            Long targetId,
            String reason,
            LocalDateTime dueAt,
            String priority,
            String status
    ) {
        public static CreateReviewTaskView from(ReviewTask task) {
            return new CreateReviewTaskView(
                    task.getId(), task.getTargetType(), task.getTargetId(),
                    task.getReason(), task.getDueAt(), task.getPriority(),
                    task.getStatus());
        }
    }
}
