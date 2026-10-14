package com.aistudy.server.ai.grading;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.provider.AiProviderException;
import com.aistudy.server.ai.service.AiUsageRecordService;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.exam.dto.ExamAttemptDto.ExamAnswerGradingView;
import com.aistudy.server.exam.service.ExamAttemptService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI-assisted grading for subjective answers, as a suggestion only (audit
 * E-07 / requirement "AI never submits the final score").
 *
 * <h3>What this class may not do</h3>
 *
 * <p>It holds no write path. The score it returns is advisory: it reaches the
 * database solely when an ADMIN posts it through the grading endpoint, which is
 * where the audit trail records the human who owns the decision. A model that
 * could set a grade directly would make exam results unauditable, and the whole
 * product rule "only ROLE_ADMIN may grade" would be a formality.
 *
 * <h3>Validation before advice</h3>
 *
 * <p>The provider is asked for a number and a reason. Anything that is not a
 * number, or falls outside {@code 0..maxScore}, is clamped into range and says
 * so in {@code clamped} rather than being shown as if the model had graded
 * within the rubric. An unparseable response is an error, never a zero — a
 * silent zero would look like a harsh but legitimate judgement.
 */
@Service
public class AiGradingSuggestionService {

    private static final String PROMPT = """
            You are grading one subjective exam answer in Chinese or English.
            Question:
            %s

            Full marks: %d
            Candidate answer:
            %s

            Return ONLY a JSON object: {"score": <integer>, "rationale": "<= 500 chars"}
            The score must be an integer between 0 and the full marks.
            Judge only what the candidate wrote; do not invent content.
            """;

    /** Longest rationale kept; the rest is dropped rather than truncating mid-word. */
    static final int MAX_RATIONALE_CHARS = 500;
    /** Longest answer text sent to the provider, so one essay cannot blow the budget. */
    static final int MAX_ANSWER_CHARS = 4000;

    private final AiProvider aiProvider;
    private final ExamAttemptService examAttemptService;
    private final AiUsageRecordService aiUsageRecordService;
    private final ObjectMapper objectMapper;

    public AiGradingSuggestionService(AiProvider aiProvider,
                                      ExamAttemptService examAttemptService,
                                      AiUsageRecordService aiUsageRecordService,
                                      ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.examAttemptService = examAttemptService;
        this.aiUsageRecordService = aiUsageRecordService;
        this.objectMapper = objectMapper;
    }

    /**
     * One suggested score for one answer.
     *
     * @param finalScore always false — a suggestion is not a grade, and the field
     *                   exists so a client cannot mistake the two
     */
    public record GradingSuggestionView(
            Long attemptId,
            Long answerId,
            Long examQuestionId,
            String questionType,
            Integer suggestedScore,
            Integer maxScore,
            String rationale,
            boolean clamped,
            String provider,
            String model,
            @Schema(description = "Always false: this is advice. The score is only"
                    + " stored once an ADMIN posts it to the grading endpoint.")
            boolean finalScore) {
    }

    /**
     * Asks the model for a score, after resolving the answer through the ADMIN
     * grading queue so the caller cannot request a suggestion for someone
     * else's space or for an objective slot.
     *
     * @param examId expected owning exam, or {@code null} to skip that check
     * @throws ApiException 404 when the answer is not in the attempt, 409
     *         {@code AI_GRADING_SUGGESTION_NOT_AVAILABLE} for a non-subjective
     *         slot or an unusable model response, 502 when the provider failed
     */
    public GradingSuggestionView suggest(String adminSubject, Long spaceId, Long examId,
                                         Long attemptId, Long answerId) {
        ExamAnswerGradingView target = examAttemptService
                .gradingQueue(spaceId, examId, attemptId).stream()
                .filter(view -> view.id().equals(answerId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound(ApiErrorCodes.EXAM_ANSWER_NOT_FOUND,
                        "answer is not part of this attempt"));
        if (!"SHORT_ANSWER".equals(target.questionType())) {
            throw ApiException.conflict(ApiErrorCodes.AI_GRADING_SUGGESTION_NOT_AVAILABLE,
                    "only subjective answers can be suggested a score: " + target.questionType());
        }
        int maxScore = target.maxScore() == null ? 0 : target.maxScore();
        String prompt = String.format(PROMPT,
                nullToEmpty(target.stem()),
                maxScore,
                truncate(nullToEmpty(target.answerText()), MAX_ANSWER_CHARS));

        AiChatResponse response = null;
        long startedNanos = System.nanoTime();
        try {
            response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.system("Return JSON only."),
                            AiChatMessage.user(prompt)),
                    0.0, 800), adminSubject);
            JsonNode node = objectMapper.readTree(stripFences(response.content()));
            if (!node.hasNonNull("score")) {
                throw ApiException.conflict(ApiErrorCodes.AI_GRADING_SUGGESTION_NOT_AVAILABLE,
                        "grading suggestion carries no score");
            }
            int raw = node.get("score").asInt(-1);
            if (raw < 0) {
                throw ApiException.conflict(ApiErrorCodes.AI_GRADING_SUGGESTION_NOT_AVAILABLE,
                        "grading suggestion score is not a non-negative integer");
            }
            boolean clamped = raw > maxScore;
            int suggested = Math.min(raw, maxScore);
            String rationale = truncate(node.path("rationale").asText(""), MAX_RATIONALE_CHARS);
            aiUsageRecordService.recordProviderCall(adminSubject, spaceId,
                    AiUsageRecordService.PURPOSE_GRADING_SUGGESTION, response,
                    AiUsageRecordService.elapsedMs(startedNanos), null);
            return new GradingSuggestionView(attemptId, answerId, target.examQuestionId(),
                    target.questionType(), suggested, maxScore, rationale, clamped,
                    response.provider(), response.model(), false);
        } catch (ApiException e) {
            aiUsageRecordService.recordProviderCall(adminSubject, spaceId,
                    AiUsageRecordService.PURPOSE_GRADING_SUGGESTION, response,
                    AiUsageRecordService.elapsedMs(startedNanos), e.code());
            throw e;
        } catch (AiProviderException e) {
            aiUsageRecordService.recordProviderCall(adminSubject, spaceId,
                    AiUsageRecordService.PURPOSE_GRADING_SUGGESTION, null,
                    AiUsageRecordService.elapsedMs(startedNanos), "AI_PROVIDER_CALL_FAILED");
            // The provider message stays in the cause for the log: detail is the
            // only text that reaches the client, and upstream failures quote
            // things we do not want to forward.
            throw new ApiException(HttpStatus.BAD_GATEWAY, ApiErrorCodes.AI_PROVIDER_UNAVAILABLE,
                    "AI grading suggestion is unavailable", e);
        } catch (Exception e) {
            aiUsageRecordService.recordProviderCall(adminSubject, spaceId,
                    AiUsageRecordService.PURPOSE_GRADING_SUGGESTION, response,
                    AiUsageRecordService.elapsedMs(startedNanos), "AI_RESPONSE_INVALID");
            throw ApiException.conflict(ApiErrorCodes.AI_GRADING_SUGGESTION_NOT_AVAILABLE,
                    "grading suggestion response could not be used");
        }
    }

    private static String stripFences(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "")
                    .replaceFirst("\\s*```$", "").trim();
        }
        return text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
