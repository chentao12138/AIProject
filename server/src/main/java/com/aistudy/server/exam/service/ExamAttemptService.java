package com.aistudy.server.exam.service;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
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
    /** Some answers are scored and a human still owes a grade on others. */
    public static final String GRADING_PARTIALLY_GRADED = "PARTIALLY_GRADED";

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
            throw ApiException.conflict(ApiErrorCodes.EXAM_NOT_AVAILABLE,
                    "exam is not available: " + exam.getStatus());
        }
        ExamPaper paper = examService.paperOf(exam);
        if (paper == null) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_NOT_AVAILABLE,
                    "exam has no paper");
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
            throw ApiException.conflict(ApiErrorCodes.EXAM_ACTIVE_ATTEMPT_EXISTS,
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
            throw ApiException.conflict(ApiErrorCodes.EXAM_ATTEMPT_STALE,
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
     * Every answer the caller already stored for one attempt, so the attempt
     * view can echo the submitted payload back (C10) and a reloaded exam page
     * can rebuild which slots were filled.
     */
    public List<ExamAnswer> answersOf(ExamAttempt attempt) {
        return examAnswerMapper.selectByAttemptId(attempt.getSpaceId(), attempt.getId());
    }

    /**
     * The persisted result of an attempt, or {@code null} when it has none yet.
     *
     * <p>Read per row while building the history list (C11): bounded by the
     * page size, which the endpoint clamps to at most 100, so this cannot turn
     * into an unbounded scan.
     */
    public ExamResult resultOrNull(ExamAttempt attempt) {
        return examResultMapper.selectByAttemptId(attempt.getSpaceId(), attempt.getId());
    }

    /**
     * Paged attempt history for the caller inside one space (C11).
     *
     * <p>Ownership is enforced twice over: the SQL pins
     * {@code ea.user_subject} and JOINs {@code learning_space.owner_subject},
     * so an {@code examId} belonging to someone else simply matches nothing
     * instead of revealing that it exists.
     *
     * @param examId optional filter to one exam
     * @param status optional lifecycle filter
     * @param page   0-based index, clamped to {@code >= 0}
     * @param size   rows per page, clamped by the caller to {@code 1..100}
     * @return the page plus the total behind the same filter
     */
    public AttemptPage listAttempts(String subject, Long spaceId, Long examId,
                                    String status, int page, int size) {
        int safePage = Math.max(page, 0);
        long total = examAttemptMapper.countByOwnerUser(
                spaceId, subject, subject, examId, status);
        List<ExamAttempt> items = examAttemptMapper.selectPageByOwnerUser(
                spaceId, subject, subject, examId, status, size, safePage * size);
        return new AttemptPage(items, total, safePage, size);
    }

    /**
     * One page of attempts with the total matching the same filter.
     *
     * @param items  attempts of this page, newest first
     * @param total  every attempt matching the filter, not just this page
     * @param page   the 0-based index served
     * @param size   the page size used
     */
    public record AttemptPage(List<ExamAttempt> items, long total, int page, int size) {
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
            throw ApiException.conflict(ApiErrorCodes.EXAM_ATTEMPT_NOT_IN_PROGRESS,
                    "exam attempt is not IN_PROGRESS: "
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
        // All five payload fields must be stored. The three-field overload used
        // here dropped orderingAnswer/matchingAnswer, so an ORDERING or MATCHING
        // answer was accepted (200) and echoed back as empty, and the attempt then
        // failed at submit against a payload that had lost its content.
        String answerJson = AnswerDataCodec.buildAnswerPayloadJson(
                payload.selectedOptionKeys(), payload.booleanAnswer(), payload.textAnswer(),
                payload.orderingAnswer(), payload.matchingAnswer());
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
            throw ApiException.conflict(ApiErrorCodes.EXAM_ATTEMPT_NOT_IN_PROGRESS,
                    "only IN_PROGRESS attempts can be submitted: " + attempt.getStatus());
        }
        assertNotExpired(attempt);

        ExamResultView result = doFinalize(ownerSubject, spaceId, attempt);
        if (result == null) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_ATTEMPT_ALREADY_SUBMITTED,
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
            throw ApiException.conflict(ApiErrorCodes.EXAM_RESULT_NOT_AVAILABLE,
                    "result is only available after submit: " + attempt.getStatus());
        }
        ExamResult result = examResultMapper.selectByAttemptId(spaceId, attemptId);
        if (result == null) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_RESULT_MISSING,
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
        // A partial score has to say it is partial (contract ask from the frontend):
        // the result page used to show only score/maxScore plus an isCorrect:null row,
        // so "objective part scored, a human still owes a grade" read as final.
        ExamAttempt current = getMine(ownerSubject, spaceId, attemptId);
        return ExamResultView.from(result, items,
                current == null ? attempt.getGradingStatus() : current.getGradingStatus(),
                countPendingGrading(new SlotAnswers(slots, answersBySlot)));
    }

    /**
     * ADMIN grading of another learner's answer (audit E-04).
     *
     * <p>{@link #getMine} binds {@code user_subject} to the caller, which is
     * exactly what made the grade endpoint unusable by an admin: every attempt of
     * a real learner answered 404 for the one role allowed to grade it.
     * Governance therefore resolves the attempt by space alone, and pins it to
     * the exam when the caller came in through
     * {@code /admin/exams/{examId}/...}.
     *
     * @param examId expected owning exam, or {@code null} to skip that check
     */
    @Transactional
    public ExamAnswerGradingView gradeAnswerAsAdmin(String adminSubject, Long spaceId, Long examId,
                                                   Long attemptId, Long answerId,
                                                   ExamAttemptDto.GradeAnswerRequest request) {
        return applyGrade(adminSubject, adminAttempt(spaceId, examId, attemptId), answerId, request);
    }

    /**
     * The grading queue: every stored answer of an attempt with its slot's
     * question type and max score plus the text the learner wrote. An ADMIN
     * cannot grade what they cannot read, and the learner's own result view is
     * not reachable across users.
     */
    public List<ExamAnswerGradingView> gradingQueue(Long spaceId, Long examId, Long attemptId) {
        ExamAttempt attempt = adminAttempt(spaceId, examId, attemptId);
        Map<Long, ExamQuestion> slotsById = new HashMap<>();
        for (ExamQuestion slot : questionsOf(attempt)) {
            slotsById.put(slot.getId(), slot);
        }
        List<ExamAnswerGradingView> views = new ArrayList<>();
        for (ExamAnswer answer : examAnswerMapper.selectByAttemptId(spaceId, attempt.getId())) {
            views.add(ExamAnswerGradingView.from(answer, slotsById.get(answer.getExamQuestionId())));
        }
        return views;
    }

    /**
     * Finalizes ADMIN grading (audit E-04): recomputes the result from the answer
     * rows as they now stand and reports the verdict the attempt actually earned.
     *
     * <p>It refuses to call an attempt final while a stored subjective answer
     * still awaits a score. The endpoint this replaces wrote
     * {@code grading_status = 'GRADED'} without grading anything, so an ungraded
     * paper looked final to the learner, to the diagnosis and to mastery.
     */
    @Transactional
    public ExamAttemptDto.AdminGradingView finalizeGradingAsAdmin(String adminSubject, Long spaceId,
                                                                 Long examId, Long attemptId) {
        ExamAttempt attempt = adminAttempt(spaceId, examId, attemptId);
        if (!STATUS_SUBMITTED.equals(attempt.getStatus())) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_RESULT_NOT_AVAILABLE,
                    "grading is only available after submit: " + attempt.getStatus());
        }
        SlotAnswers data = slotsAndAnswers(attempt, spaceId);
        int pending = countPendingGrading(data);
        if (pending > 0) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_GRADING_INCOMPLETE,
                    pending + " answer(s) still await a grade");
        }
        recomputeResultAfterGrade(adminSubject, spaceId, attempt, data);

        ExamResult result = examResultMapper.selectByAttemptId(spaceId, attemptId);
        ExamAttempt fresh = examAttemptMapper.selectByIdAndSpace(attemptId, spaceId);
        return new ExamAttemptDto.AdminGradingView(
                attemptId, attempt.getExamId(),
                fresh == null ? attempt.getStatus() : fresh.getStatus(),
                fresh == null ? null : fresh.getGradingStatus(),
                result == null ? null : result.getScore(),
                result == null ? null : result.getMaxScore(),
                0);
    }

    private ExamAttempt adminAttempt(Long spaceId, Long examId, Long attemptId) {
        ExamAttempt attempt = examAttemptMapper.selectByIdAndSpace(attemptId, spaceId);
        if (attempt == null || (examId != null && !examId.equals(attempt.getExamId()))) {
            throw ApiException.notFound(ApiErrorCodes.EXAM_ATTEMPT_NOT_FOUND,
                    "attempt is not in this space" + (examId == null ? "" : " or not of this exam"));
        }
        return attempt;
    }

    /** Answers a human still owes a score on. */
    private int countPendingGrading(SlotAnswers data) {
        int pending = 0;
        for (ExamAnswer answer : data.answers().values()) {
            if (GRADING_UNGRADED.equals(answer.getGradingStatus())
                    || GRADING_NEEDS_REVIEW.equals(answer.getGradingStatus())) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * Shared grading path behind the owner-scoped and ADMIN-scoped entry points.
     * The space always comes off the resolved attempt, never off the caller's
     * URL, so a mismatched {@code /spaces/{spaceId}} prefix cannot move a grade
     * onto another space's rows.
     */
    private ExamAnswerGradingView applyGrade(String graderSubject, ExamAttempt attempt, Long answerId,
                                            ExamAttemptDto.GradeAnswerRequest request) {
        Long spaceId = attempt.getSpaceId();
        if (!STATUS_SUBMITTED.equals(attempt.getStatus())) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_RESULT_NOT_AVAILABLE,
                    "grading is only available after submit: " + attempt.getStatus());
        }
        ExamAnswer answer = examAnswerMapper.selectByIdAndSpace(answerId, spaceId);
        if (answer == null || !attempt.getId().equals(answer.getExamAttemptId())) {
            throw ApiException.notFound(ApiErrorCodes.EXAM_ANSWER_NOT_FOUND,
                    "answer is not part of this attempt");
        }
        String gradingState = answer.getGradingStatus();
        if (!GRADING_UNGRADED.equals(gradingState) && !GRADING_NEEDS_REVIEW.equals(gradingState)
                && !GRADING_GRADED.equals(gradingState)) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_ANSWER_NOT_GRADABLE,
                    "answer is not in a gradable state: " + gradingState);
        }
        // selectByIdAndSpace takes (spaceId, examQuestionId). Passing them in
        // the call's own order made every manual grade look for
        // space_id = <slot id>, so ADMIN grading always answered 404 — no
        // subjective answer could ever be graded.
        ExamQuestion slot = examQuestionMapper.selectByIdAndSpace(
                spaceId, answer.getExamQuestionId());
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamQuestion not found");
        }
        SnapshotView slotSnapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
        if (!"SHORT_ANSWER".equals(slotSnapshot.questionType())) {
            // Objective slots are scored from the frozen snapshot. A hand-written
            // score there would end up disagreeing with is_correct, which the
            // result, the diagnosis and the mastery evidence all read.
            throw ApiException.conflict(ApiErrorCodes.EXAM_ANSWER_NOT_SUBJECTIVE,
                    "only subjective answers take a human score: " + slotSnapshot.questionType());
        }
        if (request.score() == null
                || request.score() < 0
                || (slot.getScore() != null && request.score() > slot.getScore())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.EXAM_GRADE_SCORE_OUT_OF_RANGE,
                    "score must be between 0 and " + (slot.getScore() == null ? "the slot max"
                            : slot.getScore()));
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        // A GRADED answer is accepted too: correcting a grade is the other half of
        // manual grading, and gradeUpdate keeps the first written score and
        // feedback in the previous_* columns so the correction stays auditable.
        boolean correct = slot.getScore() == null || request.score() >= slot.getScore();
        int updated = examAnswerMapper.gradeUpdate(
                answer.getId(), spaceId,
                request.score(), correct, GRADING_GRADED, request.feedback(),
                graderSubject, now, now);
        if (updated == 0) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_ANSWER_STALE,
                    "answer state changed concurrently");
        }
        answer.setScore(request.score());
        answer.setIsCorrect(correct);
        answer.setGradingStatus(GRADING_GRADED);
        answer.setFeedback(request.feedback());
        answer.setGradedBy(graderSubject);
        answer.setGradedAt(now);

        recomputeResultAfterGrade(graderSubject, spaceId, attempt, slotsAndAnswers(attempt, spaceId));
        return ExamAnswerGradingView.from(answer, slot);
    }

    private record SlotAnswers(List<ExamQuestion> slots, Map<Long, ExamAnswer> answers) {
    }

    /**
     * Grades one stored answer against its frozen snapshot, turning the
     * evaluator's shape complaint into a code that names the question. Left to
     * itself an {@code IllegalArgumentException} reached the client as
     * {@code VALIDATION_ERROR / 请求包含无法处理的参数} on submit, which told a learner
     * nothing about which of ten questions had to be answered again — and a
     * submit that fails must say, because the attempt stays IN_PROGRESS and the
     * answer can be re-posted.
     */
    private GradeResult gradeSlotOrExplain(SnapshotView snapshot, ExamQuestion slot,
                                           AnswerDataCodec.AnswerPayloadView stored) {
        try {
            return QuestionAnswerEvaluator.grade(
                    snapshot.questionType(), snapshot.answerData(),
                    AnswerPayload.of(stored.selectedOptionKeys(),
                            stored.booleanAnswer(),
                            stored.textAnswer(),
                            stored.orderingAnswer(),
                            stored.matchingAnswer()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Named by id as well as position: sortOrder is exposed as stored, and a
            // prose "question 1" that disagrees with the client's own numbering is
            // worse than no message at all.
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    ApiErrorCodes.EXAM_ANSWER_PAYLOAD_INVALID,
                    "examQuestionId=" + slot.getId() + ", sortOrder="
                            + slot.getSortOrder() + ", type=" + snapshot.questionType()
                            + " cannot be graded from the stored answer: " + e.getMessage(), e);
        }
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
        // The acting subject may be an ADMIN grading someone else's paper: the
        // result, mastery rows and diagnosis belong to the examinee, and the
        // diagnosis lookup is keyed by that subject. ownerSubject stays only as
        // the graded_by stamp on the answer.
        String examinee = attempt.getUserSubject();
        List<ExamQuestion> slots = data.slots();
        Map<Long, ExamAnswer> answersBySlot = data.answers();
        int score = 0;
        int maxScore = 0;
        int correctCount = 0;
        int wrongCount = 0;
        int unansweredCount = 0;
        int ungraded = 0;

        for (ExamQuestion slot : slots) {
            int slotScore = slot.getScore() == null ? 0 : slot.getScore();
            // ALL slots count toward paper maxScore (C-08.3).
            maxScore += slotScore;
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
            boolean correct = Boolean.TRUE.equals(answer.getIsCorrect());
            boolean subjective = "SHORT_ANSWER".equals(
                    AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson()).questionType());
            // An objective item is worth the slot's points, exactly as at submit:
            // the stored answer score is the evaluator's 1/0 marker, not paper
            // points, so summing it made a post-grading recompute lose marks.
            // A subjective item is worth whatever the grader wrote.
            score += subjective ? (answer.getScore() == null ? 0 : answer.getScore())
                    : (correct ? slotScore : 0);
            if (correct) {
                correctCount++;
            } else {
                wrongCount++;
            }
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ExamResult result = examResultMapper.selectByAttemptId(spaceId, attempt.getId());
        if (result == null) {
            result = new ExamResult();
            result.setExamAttemptId(attempt.getId());
            result.setUserSubject(examinee);
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

        String attemptGrading = ungraded > 0 ? GRADING_PARTIALLY_GRADED : GRADING_GRADED;
        examAttemptMapper.updateGradingStatusByIdAndSpace(attempt.getId(), spaceId, attemptGrading, now);

        masteryService.recomputeForQuestions(examinee, spaceId,
                slots.stream().map(ExamQuestion::getQuestionId).toList());

        examDiagnosisService.generate(examinee, spaceId, attempt.getId(), slots,
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
            GradeResult grade = gradeSlotOrExplain(snapshot, slot, storedPayload);
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

        int subjectivePending = 0;
        for (ExamQuestion slot : slots) {
            SnapshotView snap = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            if (!"SHORT_ANSWER".equals(snap.questionType())) {
                continue;
            }
            ExamAnswer a = answersBySlot.get(slot.getId());
            if (a != null && !GRADING_GRADED.equals(a.getGradingStatus())) {
                subjectivePending++;
            }
        }
        String submittedGrading = subjectivePending > 0 ? GRADING_PARTIALLY_GRADED : GRADING_GRADED;

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
            throw ApiException.conflict(ApiErrorCodes.EXAM_ATTEMPT_STALE,
                    "exam attempt state changed concurrently");
        }
        attempt.setStatus(STATUS_SUBMITTED);
        // The attempt has to label its own grading state (contract request C11):
        // a score alone cannot say whether it is final. This write used to happen
        // only for PARTIALLY_GRADED and inside catch (Exception ignore), so every
        // all-objective submission kept grading_status = null and a swallowed
        // failure was invisible.
        examAttemptMapper.updateGradingStatusByIdAndSpace(attempt.getId(), spaceId,
                submittedGrading, now);

        masteryService.recomputeForQuestions(ownerSubject, spaceId,
                slots.stream().map(ExamQuestion::getQuestionId).toList());

        examDiagnosisService.generate(ownerSubject, spaceId, attempt.getId(), slots,
                answersBySlot, score, maxScore, now);

        wrongQuestionReviewService.recordExamResults(ownerSubject, spaceId, wrongQuestionIds);

        return ExamResultView.from(result, items, submittedGrading, subjectivePending);
    }

    // ==================== internals ====================

    private void assertNotExpired(ExamAttempt attempt) {
        if (attempt.getDeadlineAt() != null && LocalDateTime.now().isAfter(attempt.getDeadlineAt())) {
            throw ApiException.conflict(ApiErrorCodes.EXAM_DEADLINE_EXCEEDED,
                    "exam deadline exceeded");
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
                // selectedOptionKeys used to be accepted here and fed through
                // Integer.valueOf, so the "legacy order" path only worked for
                // digit-shaped option keys and threw NumberFormatException for
                // A/B/C. Practice already requires orderingAnswer alone; both
                // surfaces now agree (contract request C-Ordering-space).
                if (request.orderingAnswer() == null || request.orderingAnswer().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "ORDERING requires orderingAnswer");
                }
                if (request.selectedOptionKeys() != null && !request.selectedOptionKeys().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "ORDERING answer must only carry orderingAnswer");
                }
                yield new AnswerPayload(null, null, null, request.orderingAnswer(), null);
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
