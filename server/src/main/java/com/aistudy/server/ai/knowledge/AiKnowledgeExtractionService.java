package com.aistudy.server.ai.knowledge;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.entity.KnowledgePointSource;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final KnowledgePointSourceMapper knowledgePointSourceMapper;
    private final ObjectMapper objectMapper;

    public AiKnowledgeExtractionService(AiProvider aiProvider,
                                        KnowledgePointMapper knowledgePointMapper,
                                        KnowledgePointSourceMapper knowledgePointSourceMapper,
                                        ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.knowledgePointMapper = knowledgePointMapper;
        this.knowledgePointSourceMapper = knowledgePointSourceMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts knowledge points from source text and persists them as DRAFT.
     *
     * <p>Provenance (audit F-19): every point gets a
     * {@code knowledge_point_source} row pointing at {@code sourceId}, so an
     * {@code AI_DERIVED} point can be traced back to the material it came from.
     * Block-level attribution is not possible from a job, which only carries a
     * source, so the link stops at the source.
     *
     * <p>Atomicity: the whole batch is parsed and validated BEFORE any row is
     * written, and the method is transactional. A malformed response therefore
     * cannot leave half a set of "official" knowledge points behind — the
     * previous behaviour was to insert each row as it was parsed and then
     * return an empty list on the first exception.
     *
     * @param userSubject JWT subject; becomes the space-checked creator
     * @param spaceId     owning space, taken from the job, never from the model
     * @param sourceId    the source the content came from, or {@code null} when
     *                    the caller has no source context
     * @param content     assembled source text
     * @return the persisted knowledge points, insertion-ordered
     * @throws IllegalStateException when the provider response is unusable; the
     *         job worker records it as the failure reason
     */
    @Transactional
    public List<KnowledgePoint> extract(String userSubject, Long spaceId,
                                        Long sourceId, String content) {
        String prompt = PROMPT + "\n\nContent:\n" + truncate(content, 8000);

        JsonNode arr;
        try {
            AiChatResponse response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.user(prompt)),
                    0.0,
                    2000
            ), userSubject);
            arr = objectMapper.readTree(stripFences(response.content().trim()));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI knowledge extraction failed: " + bounded(e.getMessage()), e);
        }
        if (!arr.isArray()) {
            throw new IllegalStateException(
                    "AI knowledge extraction returned a non-array payload");
        }

        // Validate the complete batch first: a row that is unusable must not
        // take the whole transaction down after other rows were already written.
        List<KnowledgePoint> parsed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (JsonNode node : arr) {
            String title = node.path("title").asText("");
            String summary = node.path("summary").asText("");
            if (title.isBlank() || summary.isBlank()) {
                throw new IllegalStateException(
                        "AI knowledge extraction produced an incomplete entry");
            }
            KnowledgePoint kp = new KnowledgePoint();
            kp.setSpaceId(spaceId);
            kp.setTitle(truncate(title, 120));
            kp.setSummary(truncate(summary, 500));
            kp.setContent(truncate(summary, 500));
            kp.setOriginType("AI_DERIVED");
            // DRAFT only: AI never publishes, and ordinary reads stay gated on
            // status='PUBLISHED' until a human reviews this.
            kp.setStatus("DRAFT");
            kp.setCreatedByUserId(userSubject);
            kp.setCreatedAt(now);
            kp.setUpdatedAt(now);
            parsed.add(kp);
        }
        if (parsed.isEmpty()) {
            throw new IllegalStateException(
                    "AI knowledge extraction produced no entries");
        }

        List<KnowledgePoint> created = new ArrayList<>();
        for (KnowledgePoint kp : parsed) {
            knowledgePointMapper.insert(kp);
            if (sourceId != null) {
                KnowledgePointSource link = new KnowledgePointSource();
                link.setSpaceId(spaceId);
                link.setKnowledgePointId(kp.getId());
                link.setSourceId(sourceId);
                link.setRelationType("AI_DERIVED");
                link.setRelevanceScore(1.0);
                link.setCreatedByUserId(userSubject);
                link.setCreatedAt(now);
                knowledgePointSourceMapper.insert(link);
            }
            created.add(kp);
        }

        log.info("AI extracted {} knowledge points for space {} from source {}",
                created.size(), spaceId, sourceId);
        return created;
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

    private static String truncate(String text, int maxBytes) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        return new String(bytes, 0, maxBytes);
    }
}
