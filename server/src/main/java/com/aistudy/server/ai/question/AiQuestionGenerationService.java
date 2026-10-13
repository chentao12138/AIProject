package com.aistudy.server.ai.question;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionKnowledgePoint;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
    private final KnowledgePointMapper knowledgePointMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper;
    private final ObjectMapper objectMapper;

    public AiQuestionGenerationService(AiProvider aiProvider,
                                       KnowledgePointMapper knowledgePointMapper,
                                       QuestionMapper questionMapper,
                                       QuestionOptionMapper questionOptionMapper,
                                       QuestionKnowledgePointMapper questionKnowledgePointMapper,
                                       ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.knowledgePointMapper = knowledgePointMapper;
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.questionKnowledgePointMapper = questionKnowledgePointMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates one DRAFT question for a knowledge point the caller owns.
     *
     * <p>Validation before persistence (audit F-20): the options are parsed
     * first, their keys must be unique and non-blank, and the declared
     * {@code correctOptionKey} must actually be one of them. Writing a question
     * whose answer is not among its own options would produce an ungradeable
     * item that still counts in practice and mastery, so an inconsistent
     * response is refused instead.
     *
     * <p>Atomicity: {@code @Transactional} with no catch-and-return. Previously
     * a failure after {@code questionMapper.insert} left an orphan DRAFT with no
     * options, and the {@code null} return hid that from the worker, which then
     * reported "produced no result" rather than a real failure.
     *
     * <p>Provenance: the KP link is written to {@code question_knowledge_point}.
     * A source link cannot be written from here — {@code question_source} only
     * carries {@code content_block_id}, and a generation job knows the source,
     * not a block, so inventing a block id would record a false citation.
     *
     * @throws IllegalStateException when the knowledge point is not the caller's,
     *         the provider response is unusable, or the answer is inconsistent
     */
    @Transactional
    public Question generate(String userSubject, Long spaceId, Long knowledgePointId) {
        KnowledgePoint kp = knowledgePointMapper.selectByIdSpaceOwner(
                knowledgePointId, spaceId, userSubject);
        if (kp == null) {
            throw new IllegalStateException(
                    "knowledge point is not in this space or does not exist");
        }

        String prompt = PROMPT + "\n\nKnowledge Point: " + kp.getTitle()
                + "\n\nSummary: " + (kp.getSummary() != null ? kp.getSummary() : kp.getContent());

        JsonNode node;
        try {
            AiChatResponse response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.user(prompt)),
                    0.0,
                    2000
            ), userSubject);
            node = objectMapper.readTree(stripFences(response.content().trim()));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI question generation failed: " + bounded(e.getMessage()), e);
        }

        String questionType = node.path("questionType").asText("SINGLE_CHOICE");
        String stem = node.path("stem").asText("");
        String explanation = node.path("explanation").asText("");
        String difficulty = node.path("difficulty").asText("MEDIUM");
        String correctOptionKey = node.path("correctOptionKey").asText("");
        if (stem.isBlank() || correctOptionKey.isBlank()) {
            throw new IllegalStateException(
                    "AI question generation returned an empty stem or answer");
        }

        List<String[]> options = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        JsonNode optionsNode = node.get("options");
        if (optionsNode != null && optionsNode.isArray()) {
            for (JsonNode opt : optionsNode) {
                String key = opt.path("key").asText("");
                String content = opt.path("content").asText("");
                if (key.isBlank() || content.isBlank() || !keys.add(key)) {
                    throw new IllegalStateException(
                            "AI question generation returned a blank or duplicate option key");
                }
                options.add(new String[]{key, content});
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

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Question question = new Question();
        question.setSpaceId(spaceId);
        question.setQuestionType(questionType);
        question.setStem(stem);
        question.setExplanation(explanation);
        question.setDifficulty(difficulty);
        question.setOriginType("AI_DERIVED");
        // DRAFT: publishing stays a human decision (the search/AI read path is
        // gated on status='PUBLISHED').
        question.setStatus("DRAFT");
        question.setCreatedByUserId(userSubject);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);
        question.setAnswerDataJson(
                AnswerDataCodec.buildAnswerDataJson(correctOptionKey, null, null, null));
        questionMapper.insert(question);

        int sortOrder = 0;
        for (String[] option : options) {
            com.aistudy.server.question.entity.QuestionOption entity =
                    new com.aistudy.server.question.entity.QuestionOption();
            entity.setSpaceId(spaceId);
            entity.setQuestionId(question.getId());
            entity.setOptionKey(option[0]);
            entity.setContent(option[1]);
            entity.setSortOrder(sortOrder++);
            entity.setCreatedAt(now);
            questionOptionMapper.insert(entity);
        }

        QuestionKnowledgePoint link = new QuestionKnowledgePoint();
        link.setSpaceId(spaceId);
        link.setQuestionId(question.getId());
        link.setKnowledgePointId(knowledgePointId);
        link.setWeight(1);
        link.setCreatedAt(now);
        questionKnowledgePointMapper.insert(link);

        log.info("AI generated question {} for knowledge point {}",
                question.getId(), knowledgePointId);
        return question;
    }

    /** Keeps a provider/JSON error message short enough to store safely. */
    private static String bounded(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
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
}
