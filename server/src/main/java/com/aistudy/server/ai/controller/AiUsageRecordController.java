package com.aistudy.server.ai.controller;

import com.aistudy.server.ai.entity.AiUsageRecord;
import com.aistudy.server.ai.service.AiUsageRecordService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/usage")
@SecurityRequirement(name = "bearerAuth")
public class AiUsageRecordController {

    private final AiUsageRecordService aiUsageRecordService;

    public AiUsageRecordController(AiUsageRecordService aiUsageRecordService) {
        this.aiUsageRecordService = aiUsageRecordService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AiUsageRecord> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    Authentication authentication) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        int offset = safePage * safeSize;
        String userSubject = authentication.getName();
        List<AiUsageRecord> records = aiUsageRecordService.list(
                userSubject, offset, safeSize);
        if (records == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return records;
    }
}
