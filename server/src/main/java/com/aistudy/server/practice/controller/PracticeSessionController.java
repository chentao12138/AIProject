package com.aistudy.server.practice.controller;

import com.aistudy.server.practice.dto.CreatePracticeSessionRequest;
import com.aistudy.server.practice.dto.PracticeSessionResponse.PracticeSessionDetail;
import com.aistudy.server.practice.dto.PracticeSessionResponse.PracticeSessionSummary;
import com.aistudy.server.practice.entity.PracticeSession;
import com.aistudy.server.practice.service.PracticeSessionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-009 — PracticeSession REST API.
 *
 * <pre>
 *   POST /api/v1/spaces/{spaceId}/practice-sessions   → 201 summary
 *   GET  /api/v1/spaces/{spaceId}/practice-sessions   → 200 list
 *   GET  .../practice-sessions/{sessionId}            → 200 detail
 *        (questions are SAFE snapshot views, no answer data)
 *   POST .../practice-sessions/{sessionId}/start      → 200
 *        (CREATED → IN_PROGRESS; 409 on other states)
 *   POST .../practice-sessions/{sessionId}/finish     → BUSINESS-010
 * </pre>
 *
 * <p>All endpoints Bearer-authenticated and owner+user scoped; 404
 * anti-probing; invalid lifecycle transitions → 409.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/practice-sessions")
@SecurityRequirement(name = "bearerAuth")
public class PracticeSessionController {

    private final PracticeSessionService practiceSessionService;

    public PracticeSessionController(PracticeSessionService practiceSessionService) {
        this.practiceSessionService = practiceSessionService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeSessionSummary create(@PathVariable Long spaceId,
                                         @Valid @RequestBody CreatePracticeSessionRequest request,
                                         Authentication authentication) {
        PracticeSession session = practiceSessionService.create(
                authentication.getName(), spaceId, request);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "LearningSpace, KnowledgePoint or Question not found");
        }
        return PracticeSessionSummary.from(session, sessionQuestionCount(session));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PracticeSessionSummary> list(@PathVariable Long spaceId,
                                             @RequestParam(required = false) LocalDateTime fromTime,
                                             @RequestParam(required = false) LocalDateTime toTime,
                                             @RequestParam(required = false) Long knowledgePointId,
                                             @RequestParam(required = false) Long knowledgeCategoryId,
                                             Authentication authentication) {
        List<PracticeSession> sessions = practiceSessionService.listMine(
                authentication.getName(), spaceId, fromTime, toTime,
                knowledgePointId, knowledgeCategoryId);
        if (sessions == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return sessions.stream()
                .map(s -> PracticeSessionSummary.from(s, sessionQuestionCount(s)))
                .toList();
    }

    @GetMapping(value = "/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PracticeSessionDetail get(@PathVariable Long spaceId,
                                     @PathVariable Long sessionId,
                                     Authentication authentication) {
        PracticeSession session = practiceSessionService.getMine(
                authentication.getName(), spaceId, sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PracticeSession not found");
        }
        return PracticeSessionDetail.from(session, practiceSessionService.questionsOf(session));
    }

    @PostMapping(value = "/{sessionId}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public PracticeSessionSummary start(@PathVariable Long spaceId,
                                        @PathVariable Long sessionId,
                                        Authentication authentication) {
        PracticeSession session = practiceSessionService.start(
                authentication.getName(), spaceId, sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PracticeSession not found");
        }
        return PracticeSessionSummary.from(session, sessionQuestionCount(session));
    }

    private int sessionQuestionCount(PracticeSession session) {
        return practiceSessionService.questionsOf(session).size();
    }
}
