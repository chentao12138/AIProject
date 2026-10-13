package com.aistudy.server.ai.question;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.service.AiUsageRecordService;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.service.KnowledgePointService;
import com.aistudy.server.question.dto.CreateQuestionRequest;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.service.QuestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one knowledge point into a DRAFT question through the provider.
 *
 * <p>The row is written by {@link QuestionService#createDerived}, not by this
 * class: per-type structure validation, option insertion and the same-space
 * knowledge-point checks live in the authoring module, and duplicating them
 * here meant an AI question could pass a weaker check than a human one
 * (audit F-20). This service only decides whether the model's answer is
 * consistent with the model's own options, which is a provider problem rather
 * than an authoring rule.
 */
@Service
public class AiQuestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AiQuestionGenerationService.class);

    private static final String PROMPT = """
            Generate a single-choice question for the given knowledge point.
            Return JSON with fields: questionType=SINGLE_CHOICE, stem, options (array of {key:A-D,content}), correctOptionKey, explanation, difficulty (EASY|MEDIUM|HARD).
            The correct answer must be one of the option keys.
            Example: {"questionType":"SINGLE_CHOICE","stem":"What is...","options":[{"key":"A","content":"..."}],"correctOptionKey":"B","explanation":"...","difficulty":"MEDIUM"}
            """;

    private final AiProvider aiProvider;
    private final KnowledgePointService knowledgePointService;
    private final QuestionService questionService;
    private final AiUsageRecordService aiUsageRecordService;
    private final ObjectMapper objectMapper;

    public AiQuestionGenerationService(AiProvider aiProvider,
                                       KnowledgePointService knowledgePointService,
                                       QuestionService questionService,
                                       AiUsageRecordService aiUsageRecordService,
                                       ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.knowledgePointService = knowledgePointService;
        this.questionService = questionService;
        this.aiUsageRecordService = aiUsageRecordService;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates and stores one DRAFT question for a knowledge point the caller
     * owns.
     *
     * <p>Validation before persistence: option keys must be non-blank and
     * unique, at least two options are required, and the declared
     * {@code correctOptionKey} must be one of them. An inconsistent answer would
     * otherwise produce an ungradeable item that still counts in practice and
     * mastery.
     *
     * <p>No failure is swallowed: a broken response throws instead of returning
     * {@code null}, which previously let the worker report "produced no result"
     * and hid the reason. Nothing is written when it throws.
     *
     * <p>Provenance: the knowledge-point link is written by the authoring
     * service from {@code knowledgePointIds}. A {@code question_source} row is
     * deliberately not written, because that table only references a content
     * block and a generation job knows the source, not a block — inventing a
     * block id would record a false citation.
     *
     * @param userSubject        JWT subject; also the space and ownership check
     * @param spaceId            owning space, taken from the job, never the model
     * @param knowledgePointId   the point the question is generated for
     * @return the persisted DRAFT question
     * @throws IllegalStateException when the point is not the caller's, the
     *         provider response is unusable, or the answer is inconsistent
     */
    public Question generate(String userSubject, Long spaceId, Long knowledgePointId) {
        KnowledgePoint kp = knowledgePointService.getMine(userSubject, spaceId, knowledgePointId);
        if (kp == null) {
            throw new IllegalStateException(
                    "knowledge point is not in this space or does not exist");
        }

        String prompt = PROMPT + "\n\nKnowledge Point: " + kp.getTitle()
                + "\n\nSummary: " + (kp.getSummary() != null ? kp.getSummary() : kp.getContent());

        JsonNode node;
        AiChatResponse response = null;
        long startedNanos = System.nanoTime();
        try {
            response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.user(prompt)),
                    0.0,
                    2000
            ), userSubject);
            node = objectMapper.readTree(stripFences(response.content().trim()));
        } catch (Exception e) {
            // Recorded before rethrowing: the tokens were spent even when the
            // answer is unusable, and a skipped row would hide the cost.
            aiUsageRecordService.recordProviderCall(userSubject, spaceId,
                    AiUsageRecordService.PURPOSE_QUESTION_GENERATION, response,
                    AiUsageRecordService.elapsedMs(startedNanos),
                    response == null ? "AI_PROVIDER_CALL_FAILED" : "AI_RESPONSE_INVALID");
            throw new IllegalStateException(
                    "AI question generation failed: " + bounded(e.getMessage()), e);
        }
        aiUsageRecordService.recordProviderCall(userSubject, spaceId,
                AiUsageRecordService.PURPOSE_QUESTION_GENERATION, response,
                AiUsageRecordService.elapsedMs(startedNanos), null);

        String questionType = node.path("questionType").asText("SINGLE_CHOICE");
        String stem = node.path("stem").asText("");
        String explanation = node.path("explanation").asText("");
        String difficulty = node.path("difficulty").asText("MEDIUM");
        String correctOptionKey = node.path("correctOptionKey").asText("");
        if (stem.isBlank() || correctOptionKey.isBlank()) {
            throw new IllegalStateException(
                    "AI question generation returned an empty stem or answer");
        }

        List<CreateQuestionRequest.QuestionOptionInput> options = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        JsonNode optionsNode = node.get("options");
        if (optionsNode != null && optionsNode.isArray()) {
            int sortOrder = 0;
            for (JsonNode opt : optionsNode) {
                String key = opt.path("key").asText("");
                String content = opt.path("content").asText("");
                if (key.isBlank() || content.isBlank() || !keys.add(key)) {
                    throw new IllegalStateException(
                            "AI question generation returned a blank or duplicate option key");
                }
                options.add(new CreateQuestionRequest.QuestionOptionInput(key, content, sortOrder++));
            }
        }
        if (options.size() < 2) {
            throw new IllegalStateException(
                    "AI question generation returned fewer than two options");
        }
        if (!keys.contains(correctOptionKey)) {
            throw new IllegalStateException(
                    "AI question generation's correct option is not among its options");
        }

        Question question = questionService.createDerived(userSubject, spaceId,
                new CreateQuestionRequest(questionType, stem, explanation, difficulty,
                        options, correctOptionKey, null, null, null, null, null,
                        List.of(knowledgePointId)),
                QuestionService.ORIGIN_TYPE_AI_DERIVED);
        if (question == null) {
            throw new IllegalStateException(
                    "the space or knowledge point is not available to this caller");
        }

        log.info("AI generated question {} for knowledge point {}",
                question.getId(), knowledgePointId);
        return question;
    }

    private static String stripFences(String json) {
        if (json.startsWith("```json")) {
            json = json.substring(7);
        } else if (json.startsWith("```")) {
            json = json.substring(3);
        }
        if (json.endsWith("```")) {
            json = json.substring(0, json.length() - 3);
        }
        return json.trim();
    }

    /** Keeps a provider/JSON error message short enough to store safely. */
    private static String bounded(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
