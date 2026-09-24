package com.aistudy.server.question.service;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.provider.AiProviderException;
import com.aistudy.server.ai.provider.AiProviderException;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.dto.AiVariantDto;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionKnowledgePoint;
import com.aistudy.server.question.entity.QuestionOption;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §5.8 — AI variant question generation.
 *
 * <p>Produces a structurally-valid variant of an existing question by
 * calling the configured {@link AiProvider}. The variant carries the
 * SAME question type and answer data as the original but with a
 * different stem/context. Variants are transient by default; when
 * saved they become a new Question with originType=AI_DERIVED and
 * status=DRAFT.
 */
@Service
public class AiVariantService {

    private static final String SYSTEM_PROMPT = """
            You are a question-authoring assistant.
            Generate a VARIANT of the user's question that:
            - has the SAME question type and difficulty
            - covers the SAME knowledge point(s)
            - uses a DIFFERENT scenario / context / wording
            - has the SAME correct answer structure (same option keys for choice types;
              same reference answer for fill-blank; same correct order for ordering;
              same matching pairs)
            Return ONLY a single JSON object with this shape:
            {
              "questionType": "SAME_AS_INPUT",
              "stem": "...",
              "options": [{"optionKey":"A","content":"...","sortOrder":0}, ...],
              "explanation": "brief explanation of the correct answer"
            }
            Do NOT include the answer in the JSON. Return nothing else.
            """;

    private final AiProvider aiProvider;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final LearningSpaceService learningSpaceService;
    private final ObjectMapper objectMapper;

    public AiVariantService(AiProvider aiProvider,
                            QuestionMapper questionMapper,
                            QuestionOptionMapper questionOptionMapper,
                            QuestionKnowledgePointMapper questionKnowledgePointMapper,
                            KnowledgePointMapper knowledgePointMapper,
                            LearningSpaceService learningSpaceService,
                            ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.questionKnowledgePointMapper = questionKnowledgePointMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.learningSpaceService = learningSpaceService;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates a variant of the given question.
     *
     * @return transient variant response (not persisted)
     */
    public AiVariantDto.VariantResponse generateVariant(String ownerSubject, Long spaceId,
                                                        Long questionId,
                                                        AiVariantDto.VariantRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        Question question = questionMapper.selectByIdSpaceOwner(questionId, spaceId, ownerSubject);
        if (question == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found");
        }

        List<QuestionOption> options = questionOptionMapper.selectByQuestionId(spaceId, questionId);
        List<QuestionKnowledgePoint> links = questionKnowledgePointMapper.selectByQuestionId(spaceId, questionId);

        List<String> kpTitles = new ArrayList<>();
        for (QuestionKnowledgePoint link : links) {
            KnowledgePoint kp = knowledgePointMapper.selectByIdSpaceOwner(
                    link.getKnowledgePointId(), spaceId, ownerSubject);
            if (kp != null) {
                kpTitles.add(kp.getTitle());
            }
        }

        String userPrompt = buildUserPrompt(question, options, kpTitles, request);

        List<AiChatMessage> messages = new ArrayList<>();
        messages.add(AiChatMessage.system(SYSTEM_PROMPT));
        messages.add(AiChatMessage.user(userPrompt));

        AiChatResponse response;
        try {
            response = aiProvider.chat(new AiChatRequest(messages, 0.7, 1024), ownerSubject);
        } catch (AiProviderException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI variant generation failed: " + e.getMessage());
        }

        String content = response.content() == null ? "" : response.content().trim();
        // Strip markdown fences if the model wraps JSON
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI returned invalid JSON: " + e.getMessage());
        }

        String variantType = root.path("questionType").asText(question.getQuestionType());
        if (!question.getQuestionType().equals(variantType)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI returned wrong question type: " + variantType);
        }

        String stem = root.path("stem").asText();
        if (stem == null || stem.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI returned empty stem");
        }

        List<AiVariantDto.OptionView> optionViews = new ArrayList<>();
        JsonNode optionsNode = root.path("options");
        if (optionsNode.isArray()) {
            for (JsonNode opt : optionsNode) {
                String key = opt.path("optionKey").asText();
                String optContent = opt.path("content").asText();
                int sortOrder = opt.path("sortOrder").isNumber()
                        ? opt.path("sortOrder").asInt() : optionViews.size();
                optionViews.add(new AiVariantDto.OptionView(key, optContent, sortOrder));
            }
        }

        String explanation = root.path("explanation").asText(null);

        // Preserve the original answer data so grading stays deterministic.
        return new AiVariantDto.VariantResponse(
                null, variantType, stem, optionViews,
                question.getAnswerDataJson(), explanation
        );
    }

    /**
     * Persists a previously-generated variant as a new Question.
     *
     * @return saved question summary
     */
    @Transactional
    public AiVariantDto.SavedVariantResponse saveVariant(String ownerSubject, Long spaceId,
                                                         Long questionId,
                                                         AiVariantDto.VariantResponse variant,
                                                         List<Long> knowledgePointIds) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        Question original = questionMapper.selectByIdSpaceOwner(questionId, spaceId, ownerSubject);
        if (original == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original question not found");
        }

        // Validate knowledge point ids (must belong to the same space).
        if (knowledgePointIds != null) {
            for (Long kpId : knowledgePointIds) {
                if (knowledgePointMapper.selectByIdSpaceOwner(kpId, spaceId, ownerSubject) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "KnowledgePoint not found: " + kpId);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Question saved = new Question();
        saved.setSpaceId(spaceId);
        saved.setQuestionType(variant.questionType());
        saved.setStem(variant.stem());
        saved.setExplanation(variant.explanation());
        saved.setDifficulty(original.getDifficulty());
        saved.setOriginType(QuestionService.ORIGIN_TYPE_AI_DERIVED);
        saved.setStatus(QuestionService.STATUS_DRAFT);
        saved.setCreatedByUserId(ownerSubject);
        saved.setCreatedAt(now);
        saved.setUpdatedAt(now);
        // Inherit answer data from the original so grading is deterministic.
        saved.setAnswerDataJson(variant.answerDataJson());
        questionMapper.insert(saved);

        // Insert options (reuse keys from the variant, fallback to index).
        insertOptions(saved.getId(), spaceId, variant.options());
        // Insert knowledge-point links.
        if (knowledgePointIds != null && !knowledgePointIds.isEmpty()) {
            insertKnowledgePointLinks(saved.getId(), spaceId, knowledgePointIds);
        } else {
            // Fall back to the original question's KP links.
            List<QuestionKnowledgePoint> originalLinks = questionKnowledgePointMapper
                    .selectByQuestionId(spaceId, questionId);
            List<Long> originalKpIds = new ArrayList<>();
            for (QuestionKnowledgePoint link : originalLinks) {
                originalKpIds.add(link.getKnowledgePointId());
            }
            if (!originalKpIds.isEmpty()) {
                insertKnowledgePointLinks(saved.getId(), spaceId, originalKpIds);
            }
        }

        return new AiVariantDto.SavedVariantResponse(
                saved.getId(), saved.getStatus(), saved.getOriginType());
    }

    private String buildUserPrompt(Question question, List<QuestionOption> options,
                                   List<String> kpTitles, AiVariantDto.VariantRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original question type: ").append(question.getQuestionType()).append("\n");
        if (!kpTitles.isEmpty()) {
            sb.append("Knowledge points: ").append(String.join(", ", kpTitles)).append("\n");
        }
        sb.append("Difficulty: ").append(question.getDifficulty() != null
                ? question.getDifficulty() : "medium").append("\n\n");
        sb.append("Original question:\n");
        sb.append("Stem: ").append(question.getStem()).append("\n");
        if (options != null && !options.isEmpty()) {
            sb.append("Options:\n");
            for (QuestionOption opt : options) {
                sb.append("  ").append(opt.getOptionKey())
                        .append(": ").append(opt.getContent()).append("\n");
            }
        }
        if (question.getAnswerDataJson() != null) {
            sb.append("Answer structure: ").append(question.getAnswerDataJson()).append("\n");
        }
        if (request.instructions() != null && !request.instructions().isBlank()) {
            sb.append("\nAdditional instructions: ").append(request.instructions()).append("\n");
        }
        return sb.toString();
    }

    private void insertOptions(Long questionId, Long spaceId,
                               List<AiVariantDto.OptionView> views) {
        if (views == null || views.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int index = 0;
        for (AiVariantDto.OptionView view : views) {
            QuestionOption option = new QuestionOption();
            option.setSpaceId(spaceId);
            option.setQuestionId(questionId);
            option.setOptionKey(view.optionKey());
            option.setContent(view.content());
            option.setSortOrder(view.sortOrder() != null ? view.sortOrder() : index);
            option.setCreatedAt(now);
            questionOptionMapper.insert(option);
            index++;
        }
    }

    private void insertKnowledgePointLinks(Long questionId, Long spaceId,
                                           List<Long> kpIds) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (Long kpId : kpIds) {
            QuestionKnowledgePoint link = new QuestionKnowledgePoint();
            link.setSpaceId(spaceId);
            link.setQuestionId(questionId);
            link.setKnowledgePointId(kpId);
            link.setCreatedAt(now);
            questionKnowledgePointMapper.insert(link);
        }
    }
}
