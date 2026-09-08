package com.aistudy.server.mastery.controller;

import com.aistudy.server.mastery.dto.MasteryResponse;
import com.aistudy.server.mastery.service.MasteryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * BUSINESS-014 — Mastery REST API (read-only; scores are never
 * client-submitted, they come from practice/exam/review evidence).
 *
 * <pre>
 *   GET /api/v1/spaces/{spaceId}/mastery
 *       → 200 all rows of the caller (weakest first)
 *   GET /api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery
 *       → 200 single row, 404 when no evidence exists yet
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
@SecurityRequirement(name = "bearerAuth")
public class MasteryController {

    private final MasteryService masteryService;

    public MasteryController(MasteryService masteryService) {
        this.masteryService = masteryService;
    }

    @GetMapping(value = "/mastery", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MasteryResponse> list(@PathVariable Long spaceId,
                                      Authentication authentication) {
        var rows = masteryService.listMine(authentication.getName(), spaceId);
        if (rows == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return rows.stream().map(MasteryResponse::from).toList();
    }

    @GetMapping(value = "/knowledge-points/{kpId}/mastery",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public MasteryResponse get(@PathVariable Long spaceId,
                               @PathVariable Long kpId,
                               Authentication authentication) {
        var mastery = masteryService.getMine(authentication.getName(), spaceId, kpId);
        if (mastery == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mastery not found");
        }
        return MasteryResponse.from(mastery);
    }
}
