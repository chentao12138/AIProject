package com.aistudy.server.exam.service;

import com.aistudy.server.exam.dto.ExamAttemptDto;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerGradingView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerRequest;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamResultItemView;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamResultView;
import com.aistudy.server.exam.entity.Exam;
import com.aistudy.server.exam.entity.ExamAnswer;
import com.aistudy.server.exam.entity.ExamAttempt;
import com.aistudy.server.exam.entity.ExamPaper;
import com.aistudy.server.exam.entity.ExamQuestion;
import com.aistudy.server.exam.entity.ExamResult;
import com.aistudy.server.exam.mapper.ExamAnswerMapper;
import com.aistudy.server.exam.mapper.ExamAttemptMapper;
import com.aistudy.server.exam.mapper.ExamQuestionMapper;
import com.aistudy.server.exam.mapper.ExamResultMapper;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.eval.AnswerDataCodec.AnswerData;
import com.aistudy.server.question.eval.AnswerDataCodec.SnapshotView;
import com.aistudy.server.question.eval.QuestionAnswerEvaluator;
import com.aistudy.server.question.eval.QuestionAnswerEvaluator.AnswerPayload;
import com.aistudy.server.question.eval.QuestionAnswerEvaluator.GradeResult;
import com.aistudy.server.wrong.service.WrongQuestionReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExamAttemptService {

    public static final String STATUS_NOT_STARTED = "NOT_STARTED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    public static final String GRADING_GRADED = "GRADED";
    public static final String GRADING_UNGRADED = "UNGRADED";
    public static final String GRADING_NEEDS_REVIEW = "NEEDS_REVIEW";

    public record GradeAnswerRequest(Integer score, String feedback) {
    }

    private final ExamAttemptMapper examAttemptMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ExamResultMapper examResultMapper;
    private final ExamService examService;
    private final com.aistudy.server.mastery.service.MasteryService masteryService;
    private final ExamDiagnosisService examDiagnosisService;
    private final WrongQuestionReviewService wrongQuestionReviewService;

    public ExamAttemptService(ExamAttemptMapper examAttemptMapper,
                              ExamQuestionMapper examQuestionMapper,
                              ExamAnswerMapper examAnswerMapper,
                              ExamResultMapper examResultMapper,
                              ExamService examService,
                              com.aistudy.server.mastery.service.MasteryService masteryService,
                              ExamDiagnosisService examDiagnosisService,
                              WrongQuestionReviewService wrongQuestionReviewService) {
        this.examAttemptMapper = examAttemptMapper;
        this.examQuestionMapper = examQuestionMapper;
        this.examAnswerMapper = examAnswerMapper;
        this.examResultMapper = examResultMapper;
        this.examService = examService;
        this.masteryService = masteryService;
        this.examDiagnosisService = examDiagnosisService;
        this.wrongQuestionReviewService = wrongQuestionReviewService;
    }

    @Transactional
    public ExamAttempt create(String ownerSubject, Long spaceId, Long examId) {
        Exam exam = examService.getMine(ownerSubject, spaceId, examId);
        if (exam == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        }
        if (!ExamService.STATUS_PUBLISHED.equals(exam.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam is not available (EXAM_NOT_AVAILABLE): " + exam.getStatus());
        }
        ExamPaper paper = examService.paperOf(exam);
        if (paper == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam has no paper (EXAM_NOT_AVAILABLE)");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ExamAttempt attempt = new ExamAttempt();
        attempt.setUserSubject(ownerSubject);
        attempt.setSpaceId(spaceId);
        attempt.setExamId(examId);
        attempt.setExamPaperId(paper.getId());
        attempt.setStatus(STATUS_NOT_STARTED);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        examAttemptMapper.insert(attempt);
        return attempt;
    }

    @Transactional
    public ExamAttempt start(String ownerSubject, Long spaceId, Long attemptId) {
        ExamAttempt existing = getMine(ownerSubject, spaceId, attemptId);
        if (existing == null) {
            return null;
        }
        if (!STATUS_NOT_STARTED.equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam attempt is not NOT_STARTED: " + existing.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Exam exam = examService.getMine(ownerSubject, spaceId, existing.getExamId());
        LocalDateTime deadline = exam != null && exam.getTimeLimitMinutes() != null
                ? now.plusMinutes(exam.getTimeLimitMinutes()).truncatedTo(ChronoUnit.MICROS)
                : null;
        int updated = examAttemptMapper.startByIdAndSpace(
                attemptId, spaceId, ownerSubject,
                STATUS_NOT_STARTED, STATUS_IN_PROGRESS, now, deadline, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam attempt state changed concurrently");
        }
        existing.setStatus(STATUS_IN_PROGRESS);
        existing.setStartedAt(now);
        existing.setDeadlineAt(deadline);
        existing.setUpdatedAt(now);
        return existing;
    }

    /** ONE attempt of the caller (user + space + owner scoped). */
    public ExamAttempt getMine(String ownerSubject, Long spaceId, Long attemptId) {
        return examAttemptMapper.selectByIdSpaceOwnerUser(
                attemptId, spaceId, ownerSubject, ownerSubject);
    }

    /** The paper slots of an attempt (display order). */
    public List<ExamQuestion> questionsOf(ExamAttempt attempt) {
        return examQuestionMapper.selectByPaperId(attempt.getSpaceId(), attempt.getExamPaperId());
    }

    /**
     * Stores (or re-stores) one answer. Graded silently; the response
     * carries NO correctness fields.
     *
     * @throws ResponseStatusException 404 / 409 (state, deadline)
     */
    @Transactional
    public ExamAnswerView answer(String ownerSubject, Long spaceId, Long attemptId,
                                 ExamAnswerRequest request) {
        ExamAttempt attempt = getMine(ownerSubject, spaceId, attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
        finalizeIfExpired(attempt, ownerSubject, spaceId);
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam attempt is not IN_PROGRESS (EXAM_ATTEMPT_NOT_IN_PROGRESS): "
                            + attempt.getStatus());
        }
        assertNotExpired(attempt);

        ExamQuestion slot = examQuestionMapper.selectByIdAndPaper(
                spaceId, attempt.getExamPaperId(), request.examQuestionId());
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "examQuestionId is not part of this exam paper");
        }

        SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
        AnswerPayload payload = validatePayloadShape(snapshot.questionType(), request.answer());
        GradeResult grade = QuestionAnswerEvaluator.grade(
                snapshot.questionType(), snapshot.answerData(), payload);

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String answerJson = AnswerDataCodec.buildAnswerPayloadJson(
                payload.selectedOptionKeys(), payload.booleanAnswer(), payload.textAnswer());
        boolean objective = !"SHORT_ANSWER".equals(snapshot.questionType());
        String gradingStatus = objective ? GRADING_GRADED : GRADING_UNGRADED;

        ExamAnswer existing = examAnswerMapper.selectByAttemptAndQuestion(
                spaceId, attemptId, slot.getId());
        ExamAnswer saved;
        if (existing == null) {
            ExamAnswer answer = new ExamAnswer();
            answer.setExamAttemptId(attemptId);
            answer.setExamQuestionId(slot.getId());
            answer.setSpaceId(spaceId);
            answer.setAnswerDataJson(answerJson);
            answer.setScore(grade.score());
            answer.setIsCorrect(grade.isCorrect());
            answer.setAnsweredAt(now);
            answer.setGradingStatus(gradingStatus);
            answer.setCreatedAt(now);
            answer.setUpdatedAt(now);
            examAnswerMapper.insert(answer);
            saved = answer;
        } else {
            examAnswerMapper.updateByIdAndSpace(
                    existing.getId(), spaceId, answerJson, grade.score(),
                    grade.isCorrect(), now, gradingStatus, now);
            existing.setAnswerDataJson(answerJson);
            existing.setScore(grade.score());
            existing.setIsCorrect(grade.isCorrect());
            existing.setAnsweredAt(now);
            existing.setGradingStatus(gradingStatus);
            existing.setUpdatedAt(now);
            saved = existing;
        }
        return ExamAnswerView.from(saved);
    }

    /**
     * Submits an IN_PROGRESS attempt: grades every objective slot from
     * the frozen snapshot, persists exam_result, SUBMITTED transition.
     * Repeated submit → 409. Deadline exceeded → 409.
     *
     * @return the result view (correctness revealed here)
     */
    @Transactional
    public ExamResultView submit(String ownerSubject, Long spaceId, Long attemptId) {
        ExamAttempt attempt = getMine(ownerSubject, spaceId, attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
        finalizeIfExpired(attempt, ownerSubject, spaceId);
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only IN_PROGRESS attempts can be submitted: " + attempt.getStatus());
        }
        assertNotExpired(attempt);

        ExamResultView result = doFinalize(ownerSubject, spaceId, attempt);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam attempt already submitted");
        }
        return result;
    }

    /**
     * Returns the result of a SUBMITTED attempt (404 / 409 otherwise).
     * Lazy-guards expired attempts before the status check.
     */
    public ExamResultView getResult(String ownerSubject, Long spaceId, Long attemptId) {
        ExamAttempt attempt = getMine(ownerSubject, spaceId, attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
        finalizeIfExpired(attempt, ownerSubject, spaceId);
        if (!STATUS_SUBMITTED.equals(attempt.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "result is only available after submit: " + attempt.getStatus());
        }
        ExamResult result = examResultMapper.selectByAttemptId(spaceId, attemptId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam result missing for submitted attempt");
        }
        List<ExamQuestion> slots = questionsOf(attempt);
        Map<Long, ExamAnswer> answersBySlot = new HashMap<>();
        for (ExamAnswer answer : examAnswerMapper.selectByAttemptId(spaceId, attemptId)) {
            answersBySlot.put(answer.getExamQuestionId(), answer);
        }
        List<ExamResultItemView> items = new ArrayList<>();
        for (ExamQuestion slot : slots) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            ExamAnswer answer = answersBySlot.get(slot.getId());
            items.add(itemView(slot, snapshot,
                    answer == null || answer.getIsCorrect() == null ? null
                            : new GradeResult(answer.getIsCorrect(),
                                    answer.getScore(), correctAnswerSummary(snapshot)),
                    answer != null));
        }
        return ExamResultView.from(result, items);
    }

    /**
     * Manual subjective grading (ADMIN only at controller).
     * Validates score bounds, records audit, recomputes result +
     * diagnosis + mastery when all subjective slots are graded.
     */
    @Transactional
    public ExamAnswerGradingView gradeAnswer(String ownerSubject, Long spaceId, Long attemptId,
                                             Long answerId, ExamAttemptDto.GradeAnswerRequest request) {
        ExamAttempt attempt = getMine(ownerSubject, spaceId, attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
        if (!STATUS_SUBMITTED.equals(attempt.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "grading is only available after submit: " + attempt.getStatus());
        }
        ExamAnswer answer = examAnswerMapper.selectByIdAndSpace(answerId, spaceId);
        if (answer == null || !attempt.getId().equals(answer.getExamAttemptId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAnswer not found");
        }
        if (!GRADING_UNGRADED.equals(answer.getGradingStatus())
                && !GRADING_NEEDS_REVIEW.equals(answer.getGradingStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "answer is not in a gradable state: " + answer.getGradingStatus());
        }
        ExamQuestion slot = examQuestionMapper.selectByIdAndSpace(
                answer.getExamQuestionId(), spaceId);
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamQuestion not found");
        }
        if (request.score() == null
                || request.score() < 0
                || (slot.getScore() != null && request.score() > slot.getScore())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "score must be between 0 and slot maxScore");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Integer previousScore = answer.getScore();
        String previousFeedback = answer.getFeedback();
        int updated = examAnswerMapper.gradeUpdate(
                answer.getId(), spaceId,
                request.score(), GRADING_GRADED, request.feedback(),
                ownerSubject, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "answer state changed concurrently");
        }
        answer.setScore(request.score());
        answer.setGradingStatus(GRADING_GRADED);
        answer.setFeedback(request.feedback());
        answer.setGradedBy(ownerSubject);
        answer.setGradedAt(now);

        recomputeResultAfterGrade(ownerSubject, spaceId, attempt, slotsAndAnswers(attempt, spaceId));
        return ExamAnswerGradingView.from(answer);
    }

    private record SlotAnswers(List<ExamQuestion> slots, Map<Long, ExamAnswer> answers) {
    }

    private SlotAnswers slotsAndAnswers(ExamAttempt attempt, Long spaceId) {
        List<ExamQuestion> slots = questionsOf(attempt);
        Map<Long, ExamAnswer> answers = new HashMap<>();
        for (ExamAnswer a : examAnswerMapper.selectByAttemptId(spaceId, attempt.getId())) {
            answers.put(a.getExamQuestionId(), a);
        }
        return new SlotAnswers(slots, answers);
    }

    /**
     * Recomputes ExamResult after subjective grading.
     * maxScore includes ALL slots (objective + subjective).
     * When subjective slots remain UNGRADED/NEEDS_REVIEW the attempt
     * grading status is PARTIALLY_GRADED; otherwise GRADED.
     */
    private void recomputeResultAfterGrade(String ownerSubject, Long spaceId,
                                           ExamAttempt attempt, SlotAnswers data) {
        List<ExamQuestion> slots = data.slots();
        Map<Long, ExamAnswer> answersBySlot = data.answers();
        int score = 0;
        int maxScore = 0;
        int correctCount = 0;
        int wrongCount = 0;
        int unansweredCount = 0;
        int ungraded = 0;
        List<Long> wrongQuestionIds = new ArrayList<>();

        for (ExamQuestion slot : slots) {
            maxScore += slot.getScore() == null ? 0 : slot.getScore();
            ExamAnswer answer = answersBySlot.get(slot.getId());
            if (answer == null) {
                unansweredCount++;
                continue;
            }
            if (GRADING_UNGRADED.equals(answer.getGradingStatus())
                    || GRADING_NEEDS_REVIEW.equals(answer.getGradingStatus())) {
                ungraded++;
                continue;
            }
            if (answer.getScore() != null) {
                score += answer.getScore();
            }
            if (Boolean.TRUE.equals(answer.getIsCorrect())) {
                correctCount++;
            } else {
                wrongCount++;
                if (slot.getQuestionId() != null) {
                    wrongQuestionIds.add(slot.getQuestionId());
                }
            }
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ExamResult result = examResultMapper.selectByAttemptId(spaceId, attempt.getId());
        if (result == null) {
            result = new ExamResult();
            result.setExamAttemptId(attempt.getId());
            result.setUserSubject(ownerSubject);
            result.setSpaceId(spaceId);
            result.setCreatedAt(now);
        }
        result.setScore(score);
        result.setMaxScore(maxScore);
        result.setCorrectCount(correctCount);
        result.setWrongCount(wrongCount);
        result.setUnansweredCount(unansweredCount);
        if (result.getId() == null) {
            examResultMapper.insert(result);
        } else {
            examResultMapper.updateById(result);
        }

        String attemptGrading = ungraded > 0 ? "PARTIALLY_GRADED" : GRADING_GRADED;
        examAttemptMapper.updateGradingStatusByIdAndSpace(attempt.getId(), spaceId, attemptGrading, now);

        masteryService.recomputeForQuestions(ownerSubject, spaceId,
                slots.stream().map(ExamQuestion::getQuestionId).toList());

        examDiagnosisService.generate(ownerSubject, spaceId, attempt.getId(), slots,
                answersBySlot, score, maxScore, now);
    }

    /**
     * Sweeps expired IN_PROGRESS attempts and finalizes them idempotently.
     * Called by ExamAutoSubmitScheduler (@Scheduled).
     */
    @Transactional
    public void finalizeExpiredAttempts() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        List<ExamAttempt> expired = examAttemptMapper.selectExpiredInProgress(now);
        for (ExamAttempt attempt : expired) {
            try {
                doFinalize(attempt.getUserSubject(), attempt.getSpaceId(), attempt);
            } catch (Exception e) {
                // best-effort: log and continue
            }
        }
    }

    // ==================== internals ====================

    /**
     * If the attempt is IN_PROGRESS and past its deadline, finalizes it.
     * Otherwise a no-op. Returns whether finalize was attempted.
     */
    private boolean finalizeIfExpired(ExamAttempt attempt, String ownerSubject, Long spaceId) {
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            return false;
        }
        if (attempt.getDeadlineAt() == null || !LocalDateTime.now().isAfter(attempt.getDeadlineAt())) {
            return false;
        }
        ExamResultView result = doFinalize(ownerSubject, spaceId, attempt);
        return result != null;
    }

    /**
     * Core finalize logic: grades objective items, persists result,
     * transitions to SUBMITTED, recomputes mastery, generates
     * diagnosis, records wrong-question follow-ups.
     *
     * <p>Idempotent: returns null if the attempt is already SUBMITTED.
     */
    private ExamResultView doFinalize(String ownerSubject, Long spaceId, ExamAttempt attempt) {
        if (STATUS_SUBMITTED.equals(attempt.getStatus())) {
            return null;
        }
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            return null;
        }

        List<ExamQuestion> slots = questionsOf(attempt);
        Map<Long, ExamAnswer> answersBySlot = new HashMap<>();
        for (ExamAnswer answer : examAnswerMapper.selectByAttemptId(spaceId, attempt.getId())) {
            answersBySlot.put(answer.getExamQuestionId(), answer);
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int score = 0;
        int maxScore = 0;
        int correctCount = 0;
        int wrongCount = 0;
        int unansweredCount = 0;
        List<ExamResultItemView> items = new ArrayList<>();
        List<Long> wrongQuestionIds = new ArrayList<>();

        for (ExamQuestion slot : slots) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            boolean subjective = "SHORT_ANSWER".equals(snapshot.questionType());
            // ALL slots count toward paper maxScore (C-08.3).
            maxScore += slot.getScore() == null ? 0 : slot.getScore();
            ExamAnswer answer = answersBySlot.get(slot.getId());
            if (subjective) {
                if (answer == null) {
                    unansweredCount++;
                    items.add(itemView(slot, snapshot, null, false));
                    continue;
                }
                // Mark for ADMIN grading; score contributes 0 until graded.
                if (answer.getGradingStatus() == null
                        || GRADING_UNGRADED.equals(answer.getGradingStatus())
                        || GRADING_NEEDS_REVIEW.equals(answer.getGradingStatus())) {
                    // leave ungraded; status PARTIALLY_GRADED after insert
                }
                items.add(itemView(slot, snapshot, null, true));
                continue;
            }
            if (answer == null) {
                unansweredCount++;
                items.add(itemView(slot, snapshot, null, false));
                continue;
            }
            AnswerDataCodec.AnswerPayloadView storedPayload =
                    AnswerDataCodec.parseAnswerPayload(answer.getAnswerDataJson());
            GradeResult grade = QuestionAnswerEvaluator.grade(
                    snapshot.questionType(), snapshot.answerData(),
                    AnswerPayload.of(storedPayload.selectedOptionKeys(),
                            storedPayload.booleanAnswer(),
                            storedPayload.textAnswer(),
                            storedPayload.orderingAnswer(),
                            storedPayload.matchingAnswer()));
            boolean correct = Boolean.TRUE.equals(grade.isCorrect());
            int itemScore = correct ? slot.getScore() : 0;
            score += itemScore;
            if (correct) {
                correctCount++;
            } else {
                wrongCount++;
                wrongQuestionIds.add(slot.getQuestionId());
            }
            items.add(itemView(slot, snapshot, grade, true));
        }

        boolean hasSubjectivePending = answersBySlot.values().stream()
                .anyMatch(a -> GRADING_UNGRADED.equals(a.getGradingStatus())
                        || GRADING_NEEDS_REVIEW.equals(a.getGradingStatus())
                        || (slots.stream().anyMatch(s -> {
                            SnapshotView snap = AnswerDataCodec.parseSnapshot(s.getQuestionSnapshotJson());
                            return "SHORT_ANSWER".equals(snap.questionType())
                                    && answersBySlot.get(s.getId()) != null
                                    && !GRADING_GRADED.equals(answersBySlot.get(s.getId()).getGradingStatus());
                        })));
        // Recompute pending subjective more simply:
        hasSubjectivePending = false;
        for (ExamQuestion slot : slots) {
            SnapshotView snap = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            if (!"SHORT_ANSWER".equals(snap.questionType())) {
                continue;
            }
            ExamAnswer a = answersBySlot.get(slot.getId());
            if (a != null && !GRADING_GRADED.equals(a.getGradingStatus())) {
                hasSubjectivePending = true;
            }
        }

        int durationMs = (int) Duration.between(attempt.getStartedAt(), now).toMillis();

        ExamResult result = new ExamResult();
        result.setExamAttemptId(attempt.getId());
        result.setUserSubject(ownerSubject);
        result.setSpaceId(spaceId);
        result.setScore(score);
        result.setMaxScore(maxScore);
        result.setCorrectCount(correctCount);
        result.setWrongCount(wrongCount);
        result.setUnansweredCount(unansweredCount);
        result.setDurationMs(durationMs);
        result.setCreatedAt(now);
        examResultMapper.insert(result);

        int updated = examAttemptMapper.submitByIdAndSpace(
                attempt.getId(), spaceId, ownerSubject,
                STATUS_IN_PROGRESS, STATUS_SUBMITTED, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam attempt state changed concurrently");
        }
        attempt.setStatus(STATUS_SUBMITTED);
        if (hasSubjectivePending) {
            try {
                examAttemptMapper.updateGradingStatusByIdAndSpace(
                        attempt.getId(), spaceId, "PARTIALLY_GRADED", now);
            } catch (Exception ignore) {
            }
        }

        masteryService.recomputeForQuestions(ownerSubject, spaceId,
                slots.stream().map(ExamQuestion::getQuestionId).toList());

        examDiagnosisService.generate(ownerSubject, spaceId, attempt.getId(), slots,
                answersBySlot, score, maxScore, now);

        wrongQuestionReviewService.recordExamResults(ownerSubject, spaceId, wrongQuestionIds);

        return ExamResultView.from(result, items);
    }

    // ==================== internals ====================

    private void assertNotExpired(ExamAttempt attempt) {
        if (attempt.getDeadlineAt() != null && LocalDateTime.now().isAfter(attempt.getDeadlineAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam deadline exceeded (EXAM_DEADLINE_EXCEEDED)");
        }
    }

    private String correctAnswerSummary(SnapshotView snapshot) {
        AnswerData data = snapshot.answerData();
        if (data.correctOptionKey() != null) {
            return data.correctOptionKey();
        }
        if (data.correctOptionKeys() != null) {
            return String.join(",", data.correctOptionKeys());
        }
        if (data.correctBoolean() != null) {
            return String.valueOf(data.correctBoolean());
        }
        return null;
    }

    private ExamResultItemView itemView(ExamQuestion slot, SnapshotView snapshot,
                                        GradeResult grade, boolean answered) {
        return new ExamResultItemView(
                slot.getId(), slot.getQuestionId(),
                grade != null && Boolean.TRUE.equals(grade.isCorrect()) ? slot.getScore() : 0,
                slot.getScore(),
                grade == null ? null : grade.isCorrect(),
                grade == null ? null : grade.correctAnswerSummary(),
                snapshot.explanation(),
                answered);
    }

    private AnswerPayload validatePayloadShape(String questionType,
                                               ExamAnswerRequest.AnswerPayloadView request) {
        return switch (questionType) {
            case "SINGLE_CHOICE" -> {
                if (request.selectedOptionKeys() == null || request.selectedOptionKeys().size() != 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SINGLE_CHOICE requires exactly one selectedOptionKeys entry");
                }
                yield new AnswerPayload(request.selectedOptionKeys(), null, null, null, null);
            }
            case "MULTIPLE_CHOICE" -> {
                if (request.selectedOptionKeys() == null || request.selectedOptionKeys().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "MULTIPLE_CHOICE requires at least one selectedOptionKeys entry");
                }
                yield new AnswerPayload(request.selectedOptionKeys(), null, null, null, null);
            }
            case "TRUE_FALSE" -> {
                if (request.booleanAnswer() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "TRUE_FALSE requires booleanAnswer");
                }
                yield new AnswerPayload(null, request.booleanAnswer(), null, null, null);
            }
            case "SHORT_ANSWER" -> {
                if (request.textAnswer() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SHORT_ANSWER requires textAnswer");
                }
                yield new AnswerPayload(null, null, request.textAnswer(), null, null);
            }
            case "FILL_BLANK" -> {
                if (request.textAnswer() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "FILL_BLANK requires textAnswer");
                }
                yield new AnswerPayload(null, null, request.textAnswer(), null, null);
            }
            case "ORDERING" -> {
                if (request.orderingAnswer() != null && !request.orderingAnswer().isEmpty()) {
                    yield new AnswerPayload(null, null, null, request.orderingAnswer(), null);
                }
                if (request.selectedOptionKeys() == null || request.selectedOptionKeys().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "ORDERING requires orderingAnswer or selectedOptionKeys order");
                }
                yield new AnswerPayload(null, null, null, request.selectedOptionKeys().stream().map(Integer::valueOf).toList(), null);
            }
            case "MATCHING" -> {
                if (request.matchingAnswer() == null || request.matchingAnswer().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "MATCHING requires matchingAnswer JSON");
                }
                yield new AnswerPayload(null, null, null, null, request.matchingAnswer());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported questionType: " + questionType);
        };
    }
}
