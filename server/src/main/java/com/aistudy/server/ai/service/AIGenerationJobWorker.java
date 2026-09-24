package com.aistudy.server.ai.service;

import com.aistudy.server.ai.content.AiContentStructureService;
import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.knowledge.AiKnowledgeExtractionService;
import com.aistudy.server.ai.question.AiQuestionGenerationService;
import com.aistudy.server.ai.source.AiSourceDiagnosisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.List;

/**
 * Bounded AI generation worker. Uses DB atomic claim so only one
 * worker processes a job; never stores raw provider secrets/errors.
 */
@Component
public class AIGenerationJobWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AIGenerationJobWorker.class);
    private static final String WORKER_ID = "ai-generation-worker";

    private final AIGenerationJobService aiGenerationJobService;
    private final AiContentStructureService aiContentStructureService;
    private final AiKnowledgeExtractionService aiKnowledgeExtractionService;
    private final AiQuestionGenerationService aiQuestionGenerationService;
    private final AiSourceDiagnosisService aiSourceDiagnosisService;

    private volatile boolean running = true;
    private Thread workerThread;

    public AIGenerationJobWorker(AIGenerationJobService aiGenerationJobService,
                                 AiContentStructureService aiContentStructureService,
                                 AiKnowledgeExtractionService aiKnowledgeExtractionService,
                                 AiQuestionGenerationService aiQuestionGenerationService,
                                 AiSourceDiagnosisService aiSourceDiagnosisService) {
        this.aiGenerationJobService = aiGenerationJobService;
        this.aiContentStructureService = aiContentStructureService;
        this.aiKnowledgeExtractionService = aiKnowledgeExtractionService;
        this.aiQuestionGenerationService = aiQuestionGenerationService;
        this.aiSourceDiagnosisService = aiSourceDiagnosisService;
    }

    @Override
    public void run(ApplicationArguments args) {
        workerThread = new Thread(() -> {
            while (running) {
                try {
                    aiGenerationJobService.recoverStaleJobs();
                    List<AIGenerationJob> pending = aiGenerationJobService.listPending();
                    if (pending == null || pending.isEmpty()) {
                        Thread.sleep(5000);
                        continue;
                    }
                    for (AIGenerationJob job : pending) {
                        if (!running) {
                            break;
                        }
                        try {
                            if (!aiGenerationJobService.claim(job.getId(), WORKER_ID)) {
                                continue;
                            }
                            processJob(job);
                            int success = (job.getSuccessCount() == null ? 0 : job.getSuccessCount()) + 1;
                            aiGenerationJobService.finish(job.getId(), job.getRequester(), success, 0);
                        } catch (Exception e) {
                            log.error("AI generation job {} failed", job.getId(), e);
                            try {
                                aiGenerationJobService.fail(job.getId(), job.getRequester(),
                                        "AI_PROCESSING_ERROR",
                                        AIGenerationJobService.sanitizeSafeMessage(e));
                            } catch (Exception ignore) {
                                // already claimed/failed elsewhere
                            }
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("AI generation worker error", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, WORKER_ID);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void processJob(AIGenerationJob job) {
        String type = job.getJobType();
        if (type == null) {
            return;
        }
        switch (type) {
            case "content-structure" -> {
                int count = aiContentStructureService.structureBlocks(
                        job.getRequester(), job.getSpaceId(), job.getSourceId());
                if (count == 0) {
                    throw new IllegalStateException("no blocks structured");
                }
                job.setSuccessCount(count);
            }
            case "knowledge-extraction" -> {
                String text = aiSourceDiagnosisService.extractSourceText(
                        job.getRequester(), job.getSpaceId(), job.getSourceId());
                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("source text is empty");
                }
                int count = aiKnowledgeExtractionService.extract(
                        job.getRequester(), job.getSpaceId(), text).size();
                if (count == 0) {
                    throw new IllegalStateException("no knowledge points extracted");
                }
                job.setSuccessCount(count);
            }
            case "question-generation" -> {
                // knowledgePointId is the semantic target — never revisionId.
                Long kpId = job.getKnowledgePointId() != null
                        ? job.getKnowledgePointId() : job.getRevisionId();
                if (kpId == null) {
                    throw new IllegalStateException("knowledgePointId is required for question-generation");
                }
                var question = aiQuestionGenerationService.generate(
                        job.getRequester(), job.getSpaceId(), kpId);
                if (question == null) {
                    throw new IllegalStateException("question generation produced no result");
                }
                job.setSuccessCount(1);
            }
            default -> throw new IllegalStateException("unsupported jobType: " + type);
        }
    }
}
