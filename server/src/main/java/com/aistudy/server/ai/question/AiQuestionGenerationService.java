package com.aistudy.server.ai.question;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
    private final ObjectMapper objectMapper;

    public AiQuestionGenerationService(AiProvider aiProvider,
                                       KnowledgePointMapper knowledgePointMapper,
                                       QuestionMapper questionMapper,
                                       QuestionOptionMapper questionOptionMapper,
                                       ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.knowledgePointMapper = knowledgePointMapper;
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.objectMapper = objectMapper;
    }

    public Question generate(String userSubject, Long spaceId, Long knowledgePointId) {
        KnowledgePoint kp = knowledgePointMapper.selectByIdSpaceOwner(knowledgePointId, spaceId, userSubject);
        if (kp == null) {
            return null;
        }

        String prompt = PROMPT + "\n\nKnowledge Point: " + kp.getTitle()
                + "\n\nSummary: " + (kp.getSummary() != null ? kp.getSummary() : kp.getContent());

        try {
            AiChatResponse response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.user(prompt)),
                    0.0,
                    2000
            ), userSubject);

            String json = response.content().trim();
            json = stripFences(json);
            JsonNode node = objectMapper.readTree(json);

            String questionType = node.path("questionType").asText("SINGLE_CHOICE");
            String stem = node.path("stem").asText("");
            String explanation = node.path("explanation").asText("");
            String difficulty = node.path("difficulty").asText("MEDIUM");
            String correctOptionKey = node.path("correctOptionKey").asText("");

            if (stem.isBlank() || correctOptionKey.isBlank()) {
                return null;
            }

            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            Question question = new Question();
            question.setSpaceId(spaceId);
            question.setQuestionType(questionType);
            question.setStem(stem);
            question.setExplanation(explanation);
            question.setDifficulty(difficulty);
            question.setOriginType("AI_DERIVED");
            question.setStatus("DRAFT");
            question.setCreatedByUserId(userSubject);
            question.setCreatedAt(now);
            question.setUpdatedAt(now);
            question.setAnswerDataJson(AnswerDataCodec.buildAnswerDataJson(correctOptionKey, null, null, null));
            questionMapper.insert(question);

            JsonNode options = node.get("options");
            if (options != null && options.isArray()) {
                int sortOrder = 0;
                for (JsonNode opt : options) {
                    String key = opt.path("key").asText("");
                    String content = opt.path("content").asText("");
                    if (key.isBlank() || content.isBlank()) {
                        continue;
                    }
                    com.aistudy.server.question.entity.QuestionOption option =
                            new com.aistudy.server.question.entity.QuestionOption();
                    option.setSpaceId(spaceId);
                    option.setQuestionId(question.getId());
                    option.setOptionKey(key);
                    option.setContent(content);
                    option.setSortOrder(sortOrder++);
                    option.setCreatedAt(now);
                    questionOptionMapper.insert(option);
                }
            }

            log.info("AI generated question {} for knowledge point {}", question.getId(), knowledgePointId);
            return question;
        } catch (Exception e) {
            log.error("AI question generation failed for KP {}: {}", knowledgePointId, e.getMessage());
            return null;
        }
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
