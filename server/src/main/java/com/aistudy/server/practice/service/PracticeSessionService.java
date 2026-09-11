package com.aistudy.server.practice.service;

import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.practice.dto.CreatePracticeSessionRequest;
import com.aistudy.server.practice.entity.PracticeSession;
import com.aistudy.server.practice.entity.PracticeSessionQuestion;
import com.aistudy.server.practice.mapper.PracticeSessionMapper;
import com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionOption;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.aistudy.server.question.service.QuestionService;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BUSINESS-009 — application service for PracticeSession.
 *
 * <p>The ONLY caller of the practice mappers. Creates a fixed question
 * composition and FREEZES it as a snapshot, so later question-bank
 * edits cannot shift the session's order or grading truth.
 *
 * <h3>Selection (deterministic, no random)</h3>
 *
 * <ul>
 *   <li>{@code questionIds}: exact order preserved; duplicates → 400;
 *       any id not PUBLISHED / not same space / not owned / deleted →
 *       whole create rejected (404, zero rows).</li>
 *   <li>{@code knowledgePointId + count}: same-space point required
 *       (404); picks the point's PUBLISHED questions ordered by
 *       question.id ASC, first {@code count}; fewer available than
 *       requested → 400 (explicit contract, no silent shrink).</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 *
 * <p>{@code CREATED → IN_PROGRESS (start) → SUBMITTED (finish, 010)}.
 * Invalid transitions → 409. finish() is implemented in BUSINESS-010
 * together with answer grading.
 */
@Service
public class PracticeSessionService {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    private final PracticeSessionMapper practiceSessionMapper;
    private final PracticeSessionQuestionMapper practiceSessionQuestionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final com.aistudy.server.question.service.QuestionSnapshotService questionSnapshotService;
    private final LearningSpaceService learningSpaceService;

    public PracticeSessionService(PracticeSessionMapper practiceSessionMapper,
                                  PracticeSessionQuestionMapper practiceSessionQuestionMapper,
                                  QuestionMapper questionMapper,
                                  QuestionOptionMapper questionOptionMapper,
                                  KnowledgePointMapper knowledgePointMapper,
                                  com.aistudy.server.question.service.QuestionSnapshotService questionSnapshotService,
                                  LearningSpaceService learningSpaceService) {
        this.practiceSessionMapper = practiceSessionMapper;
        this.practiceSessionQuestionMapper = practiceSessionQuestionMapper;
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.questionSnapshotService = questionSnapshotService;
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * Creates a CREATED practice session with a frozen question
     * composition.
     *
     * @return the persisted session, or {@code null} when the space is
     *         not owned or a referenced question/point is invalid (404)
     */
    @Transactional
    public PracticeSession create(String ownerSubject, Long spaceId,
                                  CreatePracticeSessionRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        List<Question> selected = selectQuestions(ownerSubject, spaceId, request);
        if (selected == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        PracticeSession session = new PracticeSession();
        session.setUserSubject(ownerSubject);
        session.setSpaceId(spaceId);
        session.setStatus(STATUS_CREATED);
        session.setScopeJson(buildScopeJson(request));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        practiceSessionMapper.insert(session);

        int order = 0;
        for (Question question : selected) {
            PracticeSessionQuestion slot = new PracticeSessionQuestion();
            slot.setSpaceId(spaceId);
            slot.setPracticeSessionId(session.getId());
            slot.setQuestionId(question.getId());
            slot.setSortOrder(order);
            slot.setQuestionSnapshotJson(questionSnapshotService.buildSnapshot(question));
            slot.setCreatedAt(now);
            practiceSessionQuestionMapper.insert(slot);
            order++;
        }
        return session;
    }

    /**
     * Lists the caller's own sessions, newest first.
     *
     * @return sessions, or {@code null} when the space is not owned
     */
    public List<PracticeSession> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return practiceSessionMapper.selectBySpaceOwnerUser(spaceId, ownerSubject, ownerSubject);
    }

    /**
     * Returns ONE session of the caller (user + space + owner scoped).
     */
    public PracticeSession getMine(String ownerSubject, Long spaceId, Long sessionId) {
        return practiceSessionMapper.selectByIdSpaceOwnerUser(
                sessionId, spaceId, ownerSubject, ownerSubject);
    }

    /** Fixed composition of a session (display order). */
    public List<PracticeSessionQuestion> questionsOf(PracticeSession session) {
        return practiceSessionQuestionMapper.selectBySessionId(
                session.getSpaceId(), session.getId());
    }

    /**
     * Starts a CREATED session (CREATED → IN_PROGRESS, startedAt=now).
     *
     * @return the refreshed session, or {@code null} → 404
     * @throws ResponseStatusException 409 on any non-CREATED state
     */
    @Transactional
    public PracticeSession start(String ownerSubject, Long spaceId, Long sessionId) {
        PracticeSession existing = getMine(ownerSubject, spaceId, sessionId);
        if (existing == null) {
            return null;
        }
        if (!STATUS_CREATED.equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "practice session is not in CREATED state: " + existing.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = practiceSessionMapper.updateStatusByIdAndSpace(
                sessionId, spaceId, ownerSubject,
                STATUS_CREATED, STATUS_IN_PROGRESS, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "practice session state changed concurrently");
        }
        existing.setStatus(STATUS_IN_PROGRESS);
        existing.setStartedAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }

    // ==================== internals ====================

    /** Returns the selected questions or {@code null} (404). */
    private List<Question> selectQuestions(String ownerSubject, Long spaceId,
                                           CreatePracticeSessionRequest request) {
        boolean explicit = request.questionIds() != null && !request.questionIds().isEmpty();
        boolean auto = request.knowledgePointId() != null;
        if (explicit == auto) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "exactly one selection mode required: questionIds XOR knowledgePointId+count");
        }
        if (auto && request.count() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "count is required with knowledgePointId");
        }
        if (explicit) {
            List<Question> result = new ArrayList<>();
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (Long questionId : request.questionIds()) {
                if (!seen.add(questionId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "questionIds must not contain duplicates: " + questionId);
                }
                Question question = questionMapper.selectByIdSpaceOwner(
                        questionId, spaceId, ownerSubject);
                if (question == null) {
                    return null;
                }
                if (!QuestionService.STATUS_PUBLISHED.equals(question.getStatus())) {
                    return null;
                }
                result.add(question);
            }
            return result;
        }
        // Auto mode: deterministic pick (question.id ASC, first N).
        if (knowledgePointMapper.selectByIdSpaceOwner(
                request.knowledgePointId(), spaceId, ownerSubject) == null) {
            return null;
        }
        List<Question> candidates = questionMapper.selectPublishedByKnowledgePointId(
                spaceId, ownerSubject, request.knowledgePointId(), QuestionService.STATUS_PUBLISHED);
        if (candidates.size() < request.count()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "only " + candidates.size() + " published questions available for this "
                            + "knowledge point, requested " + request.count());
        }
        return new ArrayList<>(candidates.subList(0, request.count()));
    }

    private String buildScopeJson(CreatePracticeSessionRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (request.questionIds() != null && !request.questionIds().isEmpty()) {
            map.put("selectionMode", "QUESTION_IDS");
            map.put("questionIds", request.questionIds());
        } else {
            map.put("selectionMode", "KNOWLEDGE_POINT");
            map.put("knowledgePointId", request.knowledgePointId());
            map.put("count", request.count());
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize scope json", e);
        }
    }
}
