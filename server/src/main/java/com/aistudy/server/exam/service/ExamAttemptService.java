package com.aistudy.server.exam.service;

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

/**
 * BUSINESS-013 — Exam attempt application service.
 *
 * <h3>Flow</h3>
 *
 * <pre>
 *   create (POST /exams/{examId}/sessions)  → NOT_STARTED
 *   start  (POST /exam-attempts/{id}/start) → IN_PROGRESS + deadline
 *   answer (POST /exam-attempts/{id}/answers) → stored, graded SILENTLY
 *   submit (POST /exam-attempts/{id}/submit) → SUBMITTED + exam_result
 *   result (GET  /exam-attempts/{id}/result) → correctness revealed
 * </pre>
 *
 * <h3>Contracts</h3>
 *
 * <ul>
 *   <li>Only PUBLISHED exams start (draft → 409 EXAM_NOT_AVAILABLE);
 *       attempts always reference the published paper snapshot.</li>
 *   <li>Grading reuses {@link QuestionAnswerEvaluator} against the
 *       paper's frozen snapshot — identical semantic to practice.</li>
 *   <li>Deadline: when the exam declares a duration, submit (and
 *       answer) after deadline_at → 409 EXAM_DEADLINE_EXCEEDED;
 *       server clock is authoritative, no background timer.</li>
 *   <li>Repeated submit → 409; answers only while IN_PROGRESS → 409.</li>
 *   <li>Answer POST responses NEVER carry correctness (leak-free
 *       until submit); the result view reveals it.</li>
 * </ul>
 */
@Service
public class ExamAttemptService {

    public static final String STATUS_NOT_STARTED = "NOT_STARTED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    public static final String GRADING_GRADED = "GRADED";
    public static final String GRADING_UNGRADED = "UNGRADED";

    private final ExamAttemptMapper examAttemptMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ExamResultMapper examResultMapper;
    private final ExamService examService;
    private final com.aistudy.server.mastery.service.MasteryService masteryService;
    private final ExamDiagnosisService examDiagnosisService;

    public ExamAttemptService(ExamAttemptMapper examAttemptMapper,
                              ExamQuestionMapper examQuestionMapper,
                              ExamAnswerMapper examAnswerMapper,
                              ExamResultMapper examResultMapper,
                              ExamService examService,
                              com.aistudy.server.mastery.service.MasteryService masteryService,
                              ExamDiagnosisService examDiagnosisService) {
        this.examAttemptMapper = examAttemptMapper;
        this.examQuestionMapper = examQuestionMapper;
        this.examAnswerMapper = examAnswerMapper;
        this.examResultMapper = examResultMapper;
        this.examService = examService;
        this.masteryService = masteryService;
        this.examDiagnosisService = examDiagnosisService;
    }

    /**
     * Creates a NOT_STARTED attempt for a PUBLISHED exam.
     *
     * @return the persisted attempt
     * @throws ResponseStatusException 404 (exam not found/not owned),
     *         409 (exam not PUBLISHED)
     */
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

    /**
     * Starts a NOT_STARTED attempt (IN_PROGRESS + deadline).
     *
     * @return the refreshed attempt, or {@code null} → 404
     * @throws ResponseStatusException 409 on non-NOT_STARTED state
     */
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
        if (!STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only IN_PROGRESS attempts can be submitted: " + attempt.getStatus());
        }
        assertNotExpired(attempt);

        List<ExamQuestion> slots = questionsOf(attempt);
        Map<Long, ExamAnswer> answersBySlot = new HashMap<>();
        for (ExamAnswer answer : examAnswerMapper.selectByAttemptId(spaceId, attemptId)) {
            answersBySlot.put(answer.getExamQuestionId(), answer);
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int score = 0;
        int maxScore = 0;
        int correctCount = 0;
        int wrongCount = 0;
        int unansweredCount = 0;
        List<ExamResultItemView> items = new ArrayList<>();

        for (ExamQuestion slot : slots) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            boolean objective = !"SHORT_ANSWER".equals(snapshot.questionType());
            if (!objective) {
                continue; // short answers: excluded from scoring in V1
            }
            maxScore += slot.getScore();
            ExamAnswer answer = answersBySlot.get(slot.getId());
            if (answer == null) {
                unansweredCount++;
                items.add(itemView(slot, snapshot, null, false));
                continue;
            }
            AnswerDataCodec.AnswerPayloadView storedPayload =
                    AnswerDataCodec.parseAnswerPayload(answer.getAnswerDataJson());
            GradeResult grade = QuestionAnswerEvaluator.grade(
                    snapshot.questionType(), snapshot.answerData(),
                    new AnswerPayload(storedPayload.selectedOptionKeys(),
                            storedPayload.booleanAnswer(),
                            storedPayload.textAnswer()));
            boolean correct = Boolean.TRUE.equals(grade.isCorrect());
            int itemScore = correct ? slot.getScore() : 0;
            score += itemScore;
            if (correct) {
                correctCount++;
            } else {
                wrongCount++;
            }
            items.add(itemView(slot, snapshot, grade, true));
        }

        int durationMs = (int) Duration.between(attempt.getStartedAt(), now).toMillis();

        ExamResult result = new ExamResult();
        result.setExamAttemptId(attemptId);
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
                attemptId, spaceId, ownerSubject,
                STATUS_IN_PROGRESS, STATUS_SUBMITTED, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "exam attempt state changed concurrently");
        }

        // BUSINESS-014: recompute mastery of every affected knowledge
        // point AFTER the SUBMITTED transition — the evidence query's
        // status predicate must see this attempt as SUBMITTED in the
        // SAME transaction. No mastery update on mere answer saves.
        List<Long> questionIds = slots.stream()
                .map(ExamQuestion::getQuestionId)
                .toList();
        masteryService.recomputeForQuestions(ownerSubject, spaceId, questionIds);

        // BUSINESS-015: generate the structured diagnosis from the same
        // graded items, inside the same transaction (deterministic,
        // no AI; one diagnosis per attempt).
        examDiagnosisService.generate(ownerSubject, spaceId, attemptId, slots,
                answersBySlot, score, maxScore, now);

        return ExamResultView.from(result, items);
    }

    /**
     * Returns the result of a SUBMITTED attempt (404 / 409 otherwise).
     */
    public ExamResultView getResult(String ownerSubject, Long spaceId, Long attemptId) {
        ExamAttempt attempt = getMine(ownerSubject, spaceId, attemptId);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
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
                yield new AnswerPayload(request.selectedOptionKeys(), null, null);
            }
            case "MULTIPLE_CHOICE" -> {
                if (request.selectedOptionKeys() == null || request.selectedOptionKeys().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "MULTIPLE_CHOICE requires at least one selectedOptionKeys entry");
                }
                yield new AnswerPayload(request.selectedOptionKeys(), null, null);
            }
            case "TRUE_FALSE" -> {
                if (request.booleanAnswer() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "TRUE_FALSE requires booleanAnswer");
                }
                yield new AnswerPayload(null, request.booleanAnswer(), null);
            }
            case "SHORT_ANSWER" -> {
                if (request.textAnswer() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SHORT_ANSWER requires textAnswer");
                }
                yield new AnswerPayload(null, null, request.textAnswer());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported questionType: " + questionType);
        };
    }
}
