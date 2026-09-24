package com.aistudy.server.wrong.service;

import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.wrong.entity.ReviewRecord;
import com.aistudy.server.wrong.entity.ReviewState;
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
    public static final String STATUS_DISMISSED = "DISMISSED";

    public static final String TARGET_TYPE_QUESTION = "QUESTION";
    public static final String TARGET_TYPE_KNOWLEDGE_POINT = "KNOWLEDGE_POINT";

    public static final String TASK_STATUS_PENDING = "PENDING";
    public static final String TASK_STATUS_COMPLETED = "COMPLETED";

    public static final String REASON_WRONG_ANSWER = "WRONG_ANSWER";
    public static final String REASON_EXAM_DIAGNOSIS = "EXAM_DIAGNOSIS";
    public static final String REASON_LOW_MASTERY = "LOW_MASTERY";
    public static final String REASON_MANUAL = "MANUAL";
    public static final String REASON_SCHEDULED = "SCHEDULED";
    public static final String REASON_REVIEW_WRONG = "REVIEW_WRONG";
    public static final String REASON_REVIEW_CORRECT = "REVIEW_CORRECT";

    private final WrongQuestionMapper wrongQuestionMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final LearningSpaceService learningSpaceService;
    private final ReviewStateService reviewStateService;
    private final com.aistudy.server.mastery.service.MasteryService masteryService;
    private final com.aistudy.server.studyplan.service.StudyPlanService studyPlanService;

    public WrongQuestionReviewService(WrongQuestionMapper wrongQuestionMapper,
                                      ReviewTaskMapper reviewTaskMapper,
                                      ReviewRecordMapper reviewRecordMapper,
                                      LearningSpaceService learningSpaceService,
                                      ReviewStateService reviewStateService,
                                      com.aistudy.server.mastery.service.MasteryService masteryService,
                                      com.aistudy.server.studyplan.service.StudyPlanService studyPlanService) {
        this.wrongQuestionMapper = wrongQuestionMapper;
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewRecordMapper = reviewRecordMapper;
        this.learningSpaceService = learningSpaceService;
        this.reviewStateService = reviewStateService;
        this.masteryService = masteryService;
        this.studyPlanService = studyPlanService;
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

    /**
     * Propagates objective wrong answers of a submitted exam into
     * wrong_question + review_task. Called by ExamAttemptService.submit
     * within the SAME transaction.
     */
    @Transactional
    public void recordExamResults(String ownerSubject, Long spaceId,
                                  List<Long> wrongQuestionIds) {
        if (wrongQuestionIds == null || wrongQuestionIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (Long questionId : wrongQuestionIds) {
            registerWrong(ownerSubject, spaceId, questionId, now,
                    REASON_EXAM_DIAGNOSIS, false);
        }
    }

    /**
     * Creates a review task for a knowledge point whose mastery is below
     * the configured threshold.
     */
    @Transactional
    public void recordLowMastery(String ownerSubject, Long spaceId, Long knowledgePointId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ReviewSchedulePolicy.Sm2Result sm2 = ReviewSchedulePolicy.sm2Initial(0);
        LocalDateTime dueAt = now.plusDays(sm2.intervalDays()).truncatedTo(ChronoUnit.MICROS);
        ensureReviewTask(ownerSubject, spaceId, TARGET_TYPE_KNOWLEDGE_POINT, knowledgePointId,
                REASON_LOW_MASTERY, dueAt, ReviewSchedulePolicy.PRIORITY_HIGH, now);
    }

    /** Creates a manually-scheduled review task (user-initiated). */
    @Transactional
    public ReviewTask createManualTask(String ownerSubject, Long spaceId,
                                       String targetType, Long targetId,
                                       LocalDateTime dueAt, String priority, String notes) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        if (!TARGET_TYPE_QUESTION.equals(targetType) && !TARGET_TYPE_KNOWLEDGE_POINT.equals(targetType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targetType must be QUESTION or KNOWLEDGE_POINT");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ensureReviewTask(ownerSubject, spaceId, targetType, targetId,
                REASON_MANUAL, dueAt, priority != null ? priority : ReviewSchedulePolicy.PRIORITY_LOW, now);
        // Update ReviewState if present (manual trigger overrides next due date).
        reviewStateService.overrideDue(ownerSubject, spaceId, targetType, targetId, dueAt);
        return reviewTaskMapper.selectPendingByTarget(
                ownerSubject, spaceId, targetType, targetId, TASK_STATUS_PENDING);
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

    /** Dismisses a wrong question (sets dismissedAt, status → DISMISSED). */
    @Transactional
    public WrongQuestion dismissWrongQuestion(String ownerSubject, Long spaceId, Long wrongQuestionId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        WrongQuestion wq = wrongQuestionMapper.selectByIdAndSpace(wrongQuestionId, spaceId);
        if (wq == null || !ownerSubject.equals(wq.getUserSubject())) {
            return null;
        }
        if (STATUS_DISMISSED.equals(wq.getStatus())) {
            return wq;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = wrongQuestionMapper.dismissByIdAndSpace(
                wq.getId(), spaceId, ownerSubject, now, STATUS_DISMISSED, now);
        if (updated == 0) {
            return null;
        }
        wq.setDismissedAt(now);
        wq.setStatus(STATUS_DISMISSED);
        wq.setUpdatedAt(now);
        return wq;
    }

    /** Restores a dismissed wrong question (clears dismissedAt, status → ACTIVE). */
    @Transactional
    public WrongQuestion restoreWrongQuestion(String ownerSubject, Long spaceId, Long wrongQuestionId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        WrongQuestion wq = wrongQuestionMapper.selectByIdAndSpace(wrongQuestionId, spaceId);
        if (wq == null || !ownerSubject.equals(wq.getUserSubject())) {
            return null;
        }
        if (!STATUS_DISMISSED.equals(wq.getStatus())) {
            return wq;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = wrongQuestionMapper.restoreByIdAndSpace(
                wq.getId(), spaceId, ownerSubject, STATUS_ACTIVE, now);
        if (updated == 0) {
            return null;
        }
        wq.setDismissedAt(null);
        wq.setStatus(STATUS_ACTIVE);
        wq.setUpdatedAt(now);
        return wq;
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
        int quality = ReviewSchedulePolicy.RESULT_CORRECT.equals(result) ? 4 : 2;
        // Apply SM-2 exactly once per completion (C-07.6).
        if (TARGET_TYPE_QUESTION.equals(task.getTargetType())) {
            followUp = applyReviewToWrongQuestion(
                    ownerSubject, spaceId, task.getTargetId(), result, now);
            reviewStateService.applyCompletion(
                    ownerSubject, spaceId, TARGET_TYPE_QUESTION, task.getTargetId(),
                    quality, task.getReason());
            masteryService.recomputeForReviewQuestion(ownerSubject, spaceId, task.getTargetId());
        } else if (TARGET_TYPE_KNOWLEDGE_POINT.equals(task.getTargetType())) {
            ReviewState state = reviewStateService.applyCompletion(
                    ownerSubject, spaceId, TARGET_TYPE_KNOWLEDGE_POINT, task.getTargetId(),
                    quality, task.getReason());
            if (state != null) {
                followUp = new FollowUp(null, state.getNextDueAt());
            }
            masteryService.recompute(ownerSubject, spaceId, task.getTargetId());
        }
        try {
            if (TARGET_TYPE_KNOWLEDGE_POINT.equals(task.getTargetType())) {
                studyPlanService.bumpRelatedStudyTaskPriorities(
                        ownerSubject, spaceId, task.getTargetId());
            } else if (TARGET_TYPE_QUESTION.equals(task.getTargetType())
                    && followUp != null && followUp.wrongQuestionStatus() != null) {
                // no-op plan bump for question-only reviews
            }
        } catch (Exception ignore) {
            // plan bump is best-effort; review completion already committed
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
            // SM-2 already applied by completeReviewTask — do not double-apply.
            LocalDateTime nextDue = ReviewSchedulePolicy.dueAfterWrong(now);
            ensureReviewTask(ownerSubject, spaceId, TARGET_TYPE_QUESTION, questionId,
                    REASON_REVIEW_WRONG, nextDue,
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
        LocalDateTime nextDue = ReviewSchedulePolicy.dueAfterCorrect(now,
                recent != null && recent.size() >= 2
                        && ReviewSchedulePolicy.RESULT_CORRECT.equals(recent.get(1)));
        ensureReviewTask(ownerSubject, spaceId, TARGET_TYPE_QUESTION, questionId,
                REASON_REVIEW_CORRECT, nextDue,
                ReviewSchedulePolicy.priority(wq.getWrongCount()), now);
        return new FollowUp(nextStatus, nextDue);
    }
}
