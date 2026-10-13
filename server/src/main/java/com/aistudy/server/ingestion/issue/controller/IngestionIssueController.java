package com.aistudy.server.ingestion.issue.controller;

import com.aistudy.server.ingestion.issue.entity.IngestionIssue;
import com.aistudy.server.auth.service.AuthenticationService;
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
    private final AuthenticationService authenticationService;

    public IngestionIssueController(IngestionIssueService ingestionIssueService,
                                    AuthenticationService authenticationService) {
        this.ingestionIssueService = ingestionIssueService;
        this.authenticationService = authenticationService;
    }

    /**
     * {@code resolved_by} is the numeric {@code user_account.id}. Parsing the JWT
     * subject used to be the lookup, so resolving an issue as {@code dev} — any
     * non-numeric subject, which is what every real login is — threw
     * NumberFormatException and answered 500 instead of resolving.
     *
     * @return the account id, or null for a subject with no account (a synthetic
     *         test principal); the resolution is still recorded either way
     */
    private Long resolverId(String subject) {
        var account = authenticationService.findAccountBySubject(subject);
        return account == null ? null : account.getId();
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
                authentication.getName(), spaceId, issueId, resolverId(authentication.getName()));
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
                authentication.getName(), spaceId, issueId, resolverId(authentication.getName()));
        if (updated == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "IngestionIssue not found");
        }
        return updated;
    }
}
