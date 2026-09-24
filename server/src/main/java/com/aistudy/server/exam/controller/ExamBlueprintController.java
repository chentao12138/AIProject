package com.aistudy.server.exam.controller;

import com.aistudy.server.exam.dto.ExamBlueprintDto;
import com.aistudy.server.exam.dto.ExamBlueprintDto.ExamBlueprintResponse;
import com.aistudy.server.exam.service.ExamBlueprintService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/exam-blueprints")
@SecurityRequirement(name = "bearerAuth")
public class ExamBlueprintController {

    private final ExamBlueprintService examBlueprintService;

    public ExamBlueprintController(ExamBlueprintService examBlueprintService) {
        this.examBlueprintService = examBlueprintService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExamBlueprintResponse create(@PathVariable Long spaceId,
                                        @Valid @RequestBody ExamBlueprintDto.CreateExamBlueprintRequest request,
                                        Authentication authentication) {
        var bp = examBlueprintService.create(authentication.getName(), spaceId, request);
        if (bp == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return ExamBlueprintResponse.from(bp);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ExamBlueprintResponse> list(@PathVariable Long spaceId,
                                            Authentication authentication) {
        var list = examBlueprintService.listMine(authentication.getName(), spaceId);
        if (list == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return list.stream().map(ExamBlueprintResponse::from).toList();
    }

    @GetMapping(value = "/{blueprintId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamBlueprintResponse get(@PathVariable Long spaceId,
                                     @PathVariable Long blueprintId,
                                     Authentication authentication) {
        var bp = examBlueprintService.getMine(authentication.getName(), spaceId, blueprintId);
        if (bp == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamBlueprint not found");
        }
        return ExamBlueprintResponse.from(bp);
    }

    @PostMapping(value = "/{blueprintId}/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExamBlueprintDto.ExamBlueprintGenerateResponse generate(@PathVariable Long spaceId,
                                                                   @PathVariable Long blueprintId,
                                                                   Authentication authentication) {
        var result = examBlueprintService.generate(authentication.getName(), spaceId, blueprintId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamBlueprint not found or not DRAFT");
        }
        return result;
    }
}
