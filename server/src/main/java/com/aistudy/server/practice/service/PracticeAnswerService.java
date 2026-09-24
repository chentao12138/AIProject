package com.aistudy.server.practice.service;

import com.aistudy.server.practice.dto.PracticeAnswerRequest;
import com.aistudy.server.practice.dto.PracticeAnswerResponse.PracticeAnswerView;
import com.aistudy.server.practice.dto.PracticeAnswerResponse.PracticeSubmitView;
import com.aistudy.server.practice.entity.PracticeAnswer;
import com.aistudy.server.practice.entity.PracticeSession;
import com.aistudy.server.practice.entity.PracticeSessionQuestion;
import com.aistudy.server.practice.mapper.PracticeAnswerMapper;
import com.aistudy.server.practice.mapper.PracticeSessionMapper;
import com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper;
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

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BUSINESS-010 — practice answers + session finish.
 *
 * <p>Answers are graded by the SHARED {@link QuestionAnswerEvaluator}
 * against the slot's frozen snapshot (same semantic as Exam, 013).
 *
 * <h3>Upsert contract</h3>
 *
 * <p>While a session is IN_PROGRESS the latest answer per slot is
 * upserted (re-answering overwrites and re-grades). A SUBMITTED
 * session rejects answers with 409. A slot id that is not part of
 * this session (or another space/owner) → 404.
 *
 * <h3>Finish contract</h3>
 *
 * <p>{@code finish()} recomputes every objective grade from the
 * snapshot (authoritative, idempotent), writes the summary, and
 * atomically transitions IN_PROGRESS → SUBMITTED. Objective slots
 * each carry score 1 (correct) or 0; SHORT_ANSWER slots are excluded
 * from score/maxScore (ungraded in V1). Wrong-answer → WrongQuestion /
 * ReviewTask propagation is added by BUSINESS-011 inside the SAME
 * transaction (see {@link #finish}).
 */
@Service
public class PracticeAnswerService {

    private final PracticeSessionMapper practiceSessionMapper;
    private final PracticeSessionQuestionMapper practiceSessionQuestionMapper;
    private final PracticeAnswerMapper practiceAnswerMapper;
    private final com.aistudy.server.wrong.service.WrongQuestionReviewService wrongQuestionReviewService;
    private final com.aistudy.server.mastery.service.MasteryService masteryService;

    public PracticeAnswerService(PracticeSessionMapper practiceSessionMapper,
                                 PracticeSessionQuestionMapper practiceSessionQuestionMapper,
                                 PracticeAnswerMapper practiceAnswerMapper,
                                 com.aistudy.server.wrong.service.WrongQuestionReviewService wrongQuestionReviewService,
                                 com.aistudy.server.mastery.service.MasteryService masteryService) {
        this.practiceSessionMapper = practiceSessionMapper;
        this.practiceSessionQuestionMapper = practiceSessionQuestionMapper;
        this.practiceAnswerMapper = practiceAnswerMapper;
        this.wrongQuestionReviewService = wrongQuestionReviewService;
        this.masteryService = masteryService;
    }

    /**
     * Answers (or re-answers) ONE question of an IN_PROGRESS session.
     *
     * @return the answer view with immediate feedback
     * @throws ResponseStatusException 404 (unknown session/slot), 409
     *         (session not IN_PROGRESS), 400 (payload shape violation)
     */
    @Transactional
    public PracticeAnswerView answer(String ownerSubject, Long spaceId, Long sessionId,
                                     PracticeAnswerRequest request) {
        PracticeSession session = practiceSessionMapper.selectByIdSpaceOwnerUser(
                sessionId, spaceId, ownerSubject, ownerSubject);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PracticeSession not found");
        }
        if (!PracticeSessionService.STATUS_IN_PROGRESS.equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "answers are only accepted while the session is IN_PROGRESS: "
                            + session.getStatus());
        }

        PracticeSessionQuestion slot = practiceSessionQuestionMapper.selectByIdAndSession(
                spaceId, sessionId, request.practiceSessionQuestionId());
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "practiceSessionQuestionId is not part of this session");
        }

        SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
        AnswerPayload payload = validatePayloadShape(snapshot.questionType(), request.answer());
        GradeResult grade = QuestionAnswerEvaluator.grade(
                snapshot.questionType(), snapshot.answerData(), payload);

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String answerPayloadJson = AnswerDataCodec.buildAnswerPayloadJson(
                payload.selectedOptionKeys(), payload.booleanAnswer(), payload.textAnswer(),
                payload.orderingAnswer(), payload.matchingAnswer());

        PracticeAnswer existing = practiceAnswerMapper.selectBySlotId(spaceId, slot.getId());
        PracticeAnswer saved;
        if (existing == null) {
            PracticeAnswer answer = new PracticeAnswer();
            answer.setUserSubject(ownerSubject);
            answer.setSpaceId(spaceId);
            answer.setPracticeSessionQuestionId(slot.getId());
            answer.setQuestionId(slot.getQuestionId());
            answer.setAnswerDataJson(answerPayloadJson);
            answer.setIsCorrect(grade.isCorrect());
            answer.setScore(grade.score());
            answer.setSubmittedAt(now);
            answer.setDurationMs(request.durationMs());
            answer.setFeedbackJson(null);
            answer.setCorrectAnswerSummary(grade.correctAnswerSummary());
            answer.setCreatedAt(now);
            answer.setUpdatedAt(now);
            practiceAnswerMapper.insert(answer);
            saved = answer;
        } else {
            int updated = practiceAnswerMapper.updateByIdAndSpace(
                    existing.getId(), spaceId, ownerSubject,
                    answerPayloadJson, grade.isCorrect(), grade.score(),
                    now, request.durationMs(), null, grade.correctAnswerSummary(), now);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "answer row changed concurrently");
            }
            existing.setAnswerDataJson(answerPayloadJson);
            existing.setIsCorrect(grade.isCorrect());
            existing.setScore(grade.score());
            existing.setSubmittedAt(now);
            existing.setDurationMs(request.durationMs());
            existing.setUpdatedAt(now);
            saved = existing;
        }
        return PracticeAnswerView.from(saved,
                grade.correctAnswerSummary(), snapshot.explanation());
    }

    /**
     * Finishes an IN_PROGRESS session: recomputes grades from the
     * snapshots, computes the summary, marks SUBMITTED atomically.
     *
     * @return submit summary view
     * @throws ResponseStatusException 404 / 409 (not IN_PROGRESS)
     */
    @Transactional
    public PracticeSubmitView finish(String ownerSubject, Long spaceId, Long sessionId) {
        PracticeSession session = practiceSessionMapper.selectByIdSpaceOwnerUser(
                sessionId, spaceId, ownerSubject, ownerSubject);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PracticeSession not found");
        }
        if (!PracticeSessionService.STATUS_IN_PROGRESS.equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only IN_PROGRESS sessions can be finished: " + session.getStatus());
        }

        List<PracticeSessionQuestion> slots =
                practiceSessionQuestionMapper.selectBySessionId(spaceId, sessionId);
        Map<Long, PracticeAnswer> answersBySlot = new HashMap<>();
        for (PracticeAnswer answer : practiceAnswerMapper.selectBySessionId(spaceId, sessionId)) {
            answersBySlot.put(answer.getPracticeSessionQuestionId(), answer);
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int answeredCount = 0;
        int correctCount = 0;
        int score = 0;
        int maxScore = 0;
        java.util.List<Long> wrongQuestionIds = new java.util.ArrayList<>();
        for (PracticeSessionQuestion slot : slots) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            boolean objective = isObjective(snapshot.questionType());
            PracticeAnswer answer = answersBySlot.get(slot.getId());
            if (answer == null) {
                if (objective) {
                    maxScore += 1;
                }
                continue;
            }
            answeredCount++;
            if (!objective) {
                continue;
            }
            maxScore += 1;
            // Recompute from the frozen snapshot — authoritative,
            // idempotent, immune to live-question edits.
            AnswerDataCodec.AnswerPayloadView storedPayload =
                    AnswerDataCodec.parseAnswerPayload(answer.getAnswerDataJson());
            GradeResult grade = QuestionAnswerEvaluator.grade(
                    snapshot.questionType(),
                    snapshot.answerData(),
                    new AnswerPayload(storedPayload.selectedOptionKeys(),
                            storedPayload.booleanAnswer(),
                            storedPayload.textAnswer(),
                            storedPayload.orderingAnswer(),
                            storedPayload.matchingAnswer()));
            if (!java.util.Objects.equals(grade.isCorrect(), answer.getIsCorrect())
                    || !java.util.Objects.equals(grade.score(), answer.getScore())) {
                practiceAnswerMapper.updateGradeByIdAndSpace(
                        answer.getId(), spaceId, grade.isCorrect(), grade.score(), now);
                answer.setIsCorrect(grade.isCorrect());
                answer.setScore(grade.score());
            }
            if (Boolean.TRUE.equals(grade.isCorrect())) {
                correctCount++;
                score += 1;
            } else {
                wrongQuestionIds.add(slot.getQuestionId());
            }
        }

        int updated = practiceSessionMapper.finishByIdAndSpace(
                sessionId, spaceId, ownerSubject,
                PracticeSessionService.STATUS_IN_PROGRESS,
                PracticeSessionService.STATUS_SUBMITTED, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "practice session state changed concurrently");
        }

        // BUSINESS-011: wrong answers → WrongQuestion/ReviewTask in
        // the SAME transaction as the SUBMITTED transition.
        wrongQuestionReviewService.recordPracticeResults(ownerSubject, spaceId, wrongQuestionIds);

        // BUSINESS-014: recompute mastery of every affected knowledge
        // point from the session's graded evidence (same transaction).
        java.util.List<Long> answeredQuestionIds = slots.stream()
                .filter(s -> answersBySlot.containsKey(s.getId()))
                .map(PracticeSessionQuestion::getQuestionId)
                .toList();
        masteryService.recomputeForQuestions(ownerSubject, spaceId, answeredQuestionIds);

        return PracticeSubmitView.from(sessionId, slots.size(), answeredCount,
                correctCount, score, maxScore);
    }

    /**
     * Lists all answers of a practice session (space-scoped).
     */
    public List<PracticeAnswer> listAnswers(String ownerSubject, Long spaceId, Long sessionId) {
        return practiceAnswerMapper.selectBySessionId(spaceId, sessionId);
    }

    /** Shape validation per snapshot type (400 on violations). */
    private AnswerPayload validatePayloadShape(String questionType,
                                               PracticeAnswerRequest.AnswerPayloadView request) {
        return switch (questionType) {
            case "SINGLE_CHOICE" -> {
                if (request.selectedOptionKeys() == null || request.selectedOptionKeys().size() != 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SINGLE_CHOICE requires exactly one selectedOptionKeys entry");
                }
                if (request.booleanAnswer() != null || request.textAnswer() != null
                        || request.orderingAnswer() != null || request.matchingAnswer() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SINGLE_CHOICE answer must only carry selectedOptionKeys");
                }
                yield AnswerPayload.of(request.selectedOptionKeys(), null, null);
            }
            case "MULTIPLE_CHOICE" -> {
                if (request.selectedOptionKeys() == null || request.selectedOptionKeys().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "MULTIPLE_CHOICE requires at least one selectedOptionKeys entry");
                }
                if (request.booleanAnswer() != null || request.textAnswer() != null
                        || request.orderingAnswer() != null || request.matchingAnswer() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "MULTIPLE_CHOICE answer must only carry selectedOptionKeys");
                }
                yield AnswerPayload.of(request.selectedOptionKeys(), null, null);
            }
            case "TRUE_FALSE" -> {
                if (request.booleanAnswer() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "TRUE_FALSE requires booleanAnswer");
                }
                if (request.selectedOptionKeys() != null || request.textAnswer() != null
                        || request.orderingAnswer() != null || request.matchingAnswer() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "TRUE_FALSE answer must only carry booleanAnswer");
                }
                yield AnswerPayload.of(null, request.booleanAnswer(), null);
            }
            case "SHORT_ANSWER" -> {
                if (request.textAnswer() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SHORT_ANSWER requires textAnswer");
                }
                if (request.selectedOptionKeys() != null || request.booleanAnswer() != null
                        || request.orderingAnswer() != null || request.matchingAnswer() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SHORT_ANSWER answer must only carry textAnswer");
                }
                yield AnswerPayload.of(null, null, request.textAnswer());
            }
            case "FILL_BLANK" -> {
                if (request.textAnswer() == null || request.textAnswer().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "FILL_BLANK requires textAnswer (JSON array string)");
                }
                if (request.selectedOptionKeys() != null || request.booleanAnswer() != null
                        || request.orderingAnswer() != null || request.matchingAnswer() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "FILL_BLANK answer must only carry textAnswer");
                }
                yield AnswerPayload.of(null, null, request.textAnswer());
            }
            case "ORDERING" -> {
                if (request.orderingAnswer() == null || request.orderingAnswer().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "ORDERING requires orderingAnswer");
                }
                if (request.selectedOptionKeys() != null || request.booleanAnswer() != null
                        || request.textAnswer() != null || request.matchingAnswer() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "ORDERING answer must only carry orderingAnswer");
                }
                yield AnswerPayload.of(null, null, null, request.orderingAnswer(), null);
            }
            case "MATCHING" -> {
                if (request.matchingAnswer() == null || request.matchingAnswer().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "MATCHING requires matchingAnswer (JSON array string)");
                }
                if (request.selectedOptionKeys() != null || request.booleanAnswer() != null
                        || request.textAnswer() != null || request.orderingAnswer() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "MATCHING answer must only carry matchingAnswer");
                }
                yield AnswerPayload.of(null, null, null, null, request.matchingAnswer());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported questionType: " + questionType);
        };
    }

    private static boolean isObjective(String questionType) {
        return switch (questionType) {
            case "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE",
                 "FILL_BLANK", "ORDERING", "MATCHING" -> true;
            default -> false;
        };
    }
}
