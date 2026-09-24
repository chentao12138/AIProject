package com.aistudy.server.ai.knowledge;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiKnowledgeExtractionService {
    private static final Logger log = LoggerFactory.getLogger(AiKnowledgeExtractionService.class);

    private static final String PROMPT = """
            Extract knowledge points from the following content.
            Return a JSON array of objects with fields: title, summary, difficulty (EASY|MEDIUM|HARD).
            Each title must be concise (<= 120 chars). Summary must be <= 500 chars.
            Do NOT invent facts not present in the content.
            Example: [{"title":"Normal Forms","summary":"...","difficulty":"MEDIUM"}]
            """;

    private final AiProvider aiProvider;
    private final KnowledgePointMapper knowledgePointMapper;
    private final ObjectMapper objectMapper;

    public AiKnowledgeExtractionService(AiProvider aiProvider,
                                        KnowledgePointMapper knowledgePointMapper,
                                        ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.knowledgePointMapper = knowledgePointMapper;
        this.objectMapper = objectMapper;
    }

    public List<KnowledgePoint> extract(String userSubject, Long spaceId, String content) {
        String prompt = PROMPT + "\n\nContent:\n" + truncate(content, 8000);

        try {
            AiChatResponse response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.user(prompt)),
                    0.0,
                    2000
            ), userSubject);

            String json = response.content().trim();
            json = stripFences(json);
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) {
                return List.of();
            }

            List<KnowledgePoint> created = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            for (JsonNode node : arr) {
                String title = node.path("title").asText("");
                String summary = node.path("summary").asText("");
                if (title.isBlank() || summary.isBlank()) {
                    continue;
                }
                KnowledgePoint kp = new KnowledgePoint();
                kp.setSpaceId(spaceId);
                kp.setTitle(title);
                kp.setSummary(summary);
                kp.setContent(summary);
                kp.setOriginType("AI_DERIVED");
                kp.setStatus("DRAFT");
                kp.setCreatedByUserId(userSubject);
                kp.setCreatedAt(now);
                kp.setUpdatedAt(now);
                knowledgePointMapper.insert(kp);
                created.add(kp);
            }

            log.info("AI extracted {} knowledge points for space {}", created.size(), spaceId);
            return created;
        } catch (Exception e) {
            log.error("AI knowledge extraction failed for space {}: {}", spaceId, e.getMessage());
            return List.of();
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

    private static String truncate(String text, int maxBytes) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        return new String(bytes, 0, maxBytes);
    }
}
