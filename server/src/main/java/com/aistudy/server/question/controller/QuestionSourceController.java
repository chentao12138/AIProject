package com.aistudy.server.question.controller;

import com.aistudy.server.provenance.dto.SourceReference;
import com.aistudy.server.provenance.service.SourceReferenceService;
import com.aistudy.server.question.service.QuestionSourceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class QuestionSourceController {

    private final QuestionSourceService questionSourceService;
    private final SourceReferenceService sourceReferenceService;

    public QuestionSourceController(QuestionSourceService questionSourceService,
                                    SourceReferenceService sourceReferenceService) {
        this.questionSourceService = questionSourceService;
        this.sourceReferenceService = sourceReferenceService;
    }

    @PostMapping("/api/v1/spaces/{spaceId}/questions/{questionId}/source-blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public void linkSourceBlocks(@PathVariable Long spaceId,
                                 @PathVariable Long questionId,
                                 Authentication authentication,
                                 @RequestBody List<Long> blockIds) {
        questionSourceService.linkContentBlocks(authentication.getName(), spaceId, questionId, blockIds);
    }

    /** Provenance projection — Source→Page→Block navigation in one response. */
    @GetMapping("/api/v1/spaces/{spaceId}/questions/{questionId}/source-blocks")
    public List<SourceReference> listSourceBlocks(@PathVariable Long spaceId,
                                                  @PathVariable Long questionId,
                                                  Authentication authentication) {
        List<Long> blockIds = questionSourceService.listBlockIds(
                authentication.getName(), spaceId, questionId);
        return sourceReferenceService.byContentBlockIds(spaceId, blockIds);
    }

    @DeleteMapping("/api/v1/spaces/{spaceId}/questions/{questionId}/source-blocks/{blockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkSourceBlock(@PathVariable Long spaceId,
                                  @PathVariable Long questionId,
                                  @PathVariable Long blockId,
                                  Authentication authentication) {
        questionSourceService.unlinkContentBlock(authentication.getName(), spaceId, questionId, blockId);
    }
}
