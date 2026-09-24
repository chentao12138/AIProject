package com.aistudy.server.exam.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExamAutoSubmitScheduler {

    private final ExamAttemptService examAttemptService;

    public ExamAutoSubmitScheduler(ExamAttemptService examAttemptService) {
        this.examAttemptService = examAttemptService;
    }

    @Scheduled(fixedRateString = "${exam.auto-submit-interval-ms:30000}")
    public void sweepExpiredAttempts() {
        examAttemptService.finalizeExpiredAttempts();
    }
}
