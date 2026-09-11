package com.aistudy.server.wrong.controller;

import com.aistudy.server.wrong.dto.WrongReviewResponse.CompleteReviewTaskRequest;
import com.aistudy.server.wrong.dto.WrongReviewResponse.CompleteReviewTaskView;
import com.aistudy.server.wrong.dto.WrongReviewResponse.ReviewTaskView;
import com.aistudy.server.wrong.service.ReviewSchedulePolicy;
import com.aistudy.server.wrong.service.WrongQuestionReviewService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-011 — ReviewTask REST API.
 *
 * <pre>
 *   GET  /api/v1/spaces/{spaceId}/review-tasks?dueBefore= → 200 list
 *        (owner+user scoped; dueBefore ISO-8601 optional)
 *   POST /api/v1/spaces/{spaceId}/review-tasks/{taskId}/complete
 *        → 200 (PENDING → COMPLETED; result CORRECT|WRONG)
 * </pre>
 *
 * <p>Completion writes an immutable ReviewRecord, transitions the
 * task, and for QUESTION targets updates wrong_question status + the
 * next due date per {@link ReviewSchedulePolicy} (409 on
 * re-completion).
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/review-tasks")
@SecurityRequirement(name = "bearerAuth")
public class ReviewTaskController {

    private final WrongQuestionReviewService wrongQuestionReviewService;

    public ReviewTaskController(WrongQuestionReviewService wrongQuestionReviewService) {
        this.wrongQuestionReviewService = wrongQuestionReviewService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ReviewTaskView> list(@PathVariable Long spaceId,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                     LocalDateTime dueBefore,
                                     Authentication authentication) {
        var tasks = wrongQuestionReviewService.listReviewTasks(
                authentication.getName(), spaceId, dueBefore);
        if (tasks == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return tasks.stream().map(ReviewTaskView::from).toList();
    }

    @PostMapping(value = "/{taskId}/complete", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompleteReviewTaskView complete(@PathVariable Long spaceId,
                                           @PathVariable Long taskId,
                                           @Valid @RequestBody CompleteReviewTaskRequest request,
                                           Authentication authentication) {
        WrongQuestionReviewService.CompleteResult result = wrongQuestionReviewService.complete(
                authentication.getName(), spaceId, taskId,
                request.result(), request.durationMs(), request.notes());
        return new CompleteReviewTaskView(
                result.task().getId(), result.task().getStatus(), request.result(),
                result.followUp() == null ? null : result.followUp().wrongQuestionStatus(),
                result.followUp() == null ? null : result.followUp().nextDueAt(),
                result.task().getUpdatedAt());
    }
}
