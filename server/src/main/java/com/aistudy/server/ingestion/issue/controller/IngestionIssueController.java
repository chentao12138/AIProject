package com.aistudy.server.ingestion.issue.controller;

import com.aistudy.server.ingestion.issue.entity.IngestionIssue;
import com.aistudy.server.ingestion.issue.service.IngestionIssueService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/ingestion-issues")
@SecurityRequirement(name = "bearerAuth")
public class IngestionIssueController {

    private final IngestionIssueService ingestionIssueService;

    public IngestionIssueController(IngestionIssueService ingestionIssueService) {
        this.ingestionIssueService = ingestionIssueService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<IngestionIssue> list(@PathVariable Long spaceId,
                                     @RequestParam(required = false) Long jobId,
                                     Authentication authentication) {
        if (jobId != null) {
            return ingestionIssueService.listByJob(authentication.getName(), spaceId, jobId);
        }
        return List.of();
    }

    @PostMapping(value = "/{issueId}/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public IngestionIssue resolve(@PathVariable Long spaceId,
                                  @PathVariable Long issueId,
                                  Authentication authentication) {
        IngestionIssue updated = ingestionIssueService.resolve(
                authentication.getName(), spaceId, issueId, Long.parseLong(authentication.getName()));
        if (updated == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "IngestionIssue not found");
        }
        return updated;
    }

    @PostMapping(value = "/{issueId}/ignore", produces = MediaType.APPLICATION_JSON_VALUE)
    public IngestionIssue ignore(@PathVariable Long spaceId,
                                 @PathVariable Long issueId,
                                 Authentication authentication) {
        IngestionIssue updated = ingestionIssueService.ignore(
                authentication.getName(), spaceId, issueId, Long.parseLong(authentication.getName()));
        if (updated == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "IngestionIssue not found");
        }
        return updated;
    }
}
