package com.aistudy.server.wrong.service;

import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.wrong.entity.ReviewRecord;
import com.aistudy.server.wrong.entity.ReviewTask;
import com.aistudy.server.wrong.entity.WrongQuestion;
import com.aistudy.server.wrong.mapper.ReviewRecordMapper;
import com.aistudy.server.wrong.mapper.ReviewTaskMapper;
import com.aistudy.server.wrong.mapper.WrongQuestionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * BUSINESS-011 — WrongQuestion + ReviewTask application service.
 *
 * <p>Practice submit (010) calls {@link #recordPracticeResults} INSIDE
 * the submit transaction: wrong answers upsert wrong_question and
 * (re)schedule a review task atomically with the SUBMITTED
 * transition — no half-written wrong-question state.
 *
 * <h3>Review completion</h3>
 *
 * <p>{@code completeReviewTask} records the immutable ReviewRecord,
 * transitions PENDING → COMPLETED (409 otherwise), and for QUESTION
 * targets updates the wrong_question row (WRONG → ACTIVE + due +1d;
 * CORRECT → IMPROVING/MASTERED + due +3d/+7d). KNOWLEDGE_POINT
 * targets only record history (StudyPlan owns their scheduling).
 */
@Service
public class WrongQuestionReviewService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_IMPROVING = "IMPROVING";
    public static final String STATUS_MASTERED = "MASTERED";

    public static final String TARGET_TYPE_QUESTION = "QUESTION";
    public static final String TARGET_TYPE_KNOWLEDGE_POINT = "KNOWLEDGE_POINT";

    public static final String TASK_STATUS_PENDING = "PENDING";
    public static final String TASK_STATUS_COMPLETED = "COMPLETED";

    private final WrongQuestionMapper wrongQuestionMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final LearningSpaceService learningSpaceService;

    public WrongQuestionReviewService(WrongQuestionMapper wrongQuestionMapper,
                                      ReviewTaskMapper reviewTaskMapper,
                                      ReviewRecordMapper reviewRecordMapper,
                                      LearningSpaceService learningSpaceService) {
        this.wrongQuestionMapper = wrongQuestionMapper;
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewRecordMapper = reviewRecordMapper;
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * Propagates objective wrong answers of a finished practice
     * session into wrong_question + review_task. Called by
     * PracticeAnswerService.finish within the SAME transaction.
     *
     * @param wrongQuestionIds question ids answered incorrectly
     */
    @Transactional
    public void recordPracticeResults(String ownerSubject, Long spaceId,
                                      List<Long> wrongQuestionIds) {
        if (wrongQuestionIds == null || wrongQuestionIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (Long questionId : wrongQuestionIds) {
            registerWrong(ownerSubject, spaceId, questionId, now,
                    "WRONG_IN_PRACTICE", false);
        }
    }

    /** Wrong-answer registration (shared by practice + review). */
    private void registerWrong(String ownerSubject, Long spaceId, Long questionId,
                               LocalDateTime now, String reason, boolean refreshDueOnly) {
        WrongQuestion existing = wrongQuestionMapper.selectByUserSpaceQuestion(
                ownerSubject, spaceId, questionId);
        int wrongCount;
        if (existing == null) {
            WrongQuestion wq = new WrongQuestion();
            wq.setUserSubject(ownerSubject);
            wq.setSpaceId(spaceId);
            wq.setQuestionId(questionId);
            wq.setFirstWrongAt(now);
            wq.setLastWrongAt(now);
            wq.setWrongCount(1);
            wq.setStatus(STATUS_ACTIVE);
            wq.setCreatedAt(now);
            wq.setUpdatedAt(now);
            wrongQuestionMapper.insert(wq);
            wrongCount = 1;
        } else {
            wrongQuestionMapper.incrementWrong(existing.getId(), spaceId, ownerSubject,
                    now, STATUS_ACTIVE, now);
            wrongCount = existing.getWrongCount() + 1;
        }
        ensureReviewTask(ownerSubject, spaceId, TARGET_TYPE_QUESTION, questionId,
                reason, ReviewSchedulePolicy.dueAfterWrong(now),
                ReviewSchedulePolicy.priority(wrongCount), now);
    }

    /**
     * Creates a review task, or refreshes an existing PENDING one for
     * the same target (no duplicates).
     */
    private void ensureReviewTask(String ownerSubject, Long spaceId, String targetType,
                                  Long targetId, String reason, LocalDateTime dueAt,
                                  String priority, LocalDateTime now) {
        ReviewTask pending = reviewTaskMapper.selectPendingByTarget(
                ownerSubject, spaceId, targetType, targetId, TASK_STATUS_PENDING);
        if (pending != null) {
            reviewTaskMapper.rescheduleByIdAndSpace(
                    pending.getId(), spaceId, ownerSubject, dueAt, priority, reason, now);
            return;
        }
        ReviewTask task = new ReviewTask();
        task.setUserSubject(ownerSubject);
        task.setSpaceId(spaceId);
        task.setTargetType(targetType);
        task.setTargetId(targetId);
        task.setReason(reason);
        task.setDueAt(dueAt);
        task.setPriority(priority);
        task.setStatus(TASK_STATUS_PENDING);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        reviewTaskMapper.insert(task);
    }

    // ==================== reads ====================

    /** Wrong questions of the caller's space, newest wrong first. */
    public List<WrongQuestion> listWrongQuestions(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return wrongQuestionMapper.selectBySpaceOwnerUser(spaceId, ownerSubject, ownerSubject);
    }

    /** Open review tasks, optionally due before a timestamp. */
    public List<ReviewTask> listReviewTasks(String ownerSubject, Long spaceId,
                                            LocalDateTime dueBefore) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return reviewTaskMapper.selectBySpaceOwnerUser(
                spaceId, ownerSubject, ownerSubject, dueBefore);
    }

    // ==================== completion ====================

    /**
     * Completes a PENDING review task with the given result.
     *
     * @return completion result (task + wrong-question follow-up)
     * @throws ResponseStatusException 404 / 409 / 400
     */
    @Transactional
    public CompleteResult complete(String ownerSubject, Long spaceId, Long taskId,
                                   String result, Integer durationMs, String notes) {
        if (!ReviewSchedulePolicy.RESULT_CORRECT.equals(result)
                && !ReviewSchedulePolicy.RESULT_WRONG.equals(result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "result must be CORRECT or WRONG");
        }
        ReviewTask task = reviewTaskMapper.selectByIdSpaceOwnerUser(
                taskId, spaceId, ownerSubject, ownerSubject);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ReviewTask not found");
        }
        if (!TASK_STATUS_PENDING.equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "review task is not PENDING: " + task.getStatus());
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        ReviewRecord record = new ReviewRecord();
        record.setReviewTaskId(task.getId());
        record.setUserSubject(ownerSubject);
        record.setSpaceId(spaceId);
        record.setResult(result);
        record.setCompletedAt(now);
        record.setDurationMs(durationMs);
        record.setNotes(notes);
        record.setCreatedAt(now);
        reviewRecordMapper.insert(record);

        int updated = reviewTaskMapper.completeByIdAndSpace(
                taskId, spaceId, ownerSubject, TASK_STATUS_PENDING, TASK_STATUS_COMPLETED, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "review task state changed concurrently");
        }
        task.setStatus(TASK_STATUS_COMPLETED);
        task.setUpdatedAt(now);

        FollowUp followUp = null;
        if (TARGET_TYPE_QUESTION.equals(task.getTargetType())) {
            followUp = applyReviewToWrongQuestion(
                    ownerSubject, spaceId, task.getTargetId(), result, now);
        }
        return new CompleteResult(task, followUp);
    }

    /** Follow-up state after completing a QUESTION review task. */
    public record FollowUp(String wrongQuestionStatus, LocalDateTime nextDueAt) {
    }

    /** Completion outcome: task + optional wrong-question follow-up. */
    public record CompleteResult(ReviewTask task, FollowUp followUp) {
    }

    private FollowUp applyReviewToWrongQuestion(String ownerSubject, Long spaceId,
                                                Long questionId, String result, LocalDateTime now) {
        WrongQuestion wq = wrongQuestionMapper.selectByUserSpaceQuestion(
                ownerSubject, spaceId, questionId);
        if (wq == null) {
            // Reviewing a question that was never wrong: just record
            // history (no wrong_question row invented).
            return null;
        }
        if (ReviewSchedulePolicy.RESULT_WRONG.equals(result)) {
            wrongQuestionMapper.incrementWrong(wq.getId(), spaceId, ownerSubject,
                    now, STATUS_ACTIVE, now);
            LocalDateTime nextDue = ReviewSchedulePolicy.dueAfterWrong(now);
            ensureReviewTask(ownerSubject, spaceId, TARGET_TYPE_QUESTION, questionId,
                    "REVIEW_WRONG", nextDue,
                    ReviewSchedulePolicy.priority(wq.getWrongCount() + 1), now);
            return new FollowUp(STATUS_ACTIVE, nextDue);
        }
        // CORRECT: recompute status from the two most recent results
        // (the record just inserted is first).
        List<String> recent = wrongQuestionMapper.selectRecentReviewResults(
                spaceId, ownerSubject, questionId, 2);
        String nextStatus = ReviewSchedulePolicy.statusAfterReview(result, recent);
        wrongQuestionMapper.markCorrect(wq.getId(), spaceId, ownerSubject, now, nextStatus, now);
        if (STATUS_MASTERED.equals(nextStatus)) {
            return new FollowUp(STATUS_MASTERED, null);
        }
        boolean previousWasCorrect = recent != null && recent.size() >= 2
                && ReviewSchedulePolicy.RESULT_CORRECT.equals(recent.get(1));
        LocalDateTime nextDue = ReviewSchedulePolicy.dueAfterCorrect(now, previousWasCorrect);
        ensureReviewTask(ownerSubject, spaceId, TARGET_TYPE_QUESTION, questionId,
                "REVIEW_CORRECT", nextDue,
                ReviewSchedulePolicy.priority(wq.getWrongCount()), now);
        return new FollowUp(nextStatus, nextDue);
    }
}
