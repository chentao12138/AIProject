package com.aistudy.server.ai.content;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiContentStructureService {
    private static final Logger log = LoggerFactory.getLogger(AiContentStructureService.class);

    private static final String PROMPT = """
            Classify each text block into exactly one type: HEADING, PARAGRAPH, LIST, TABLE, CODE.
            Return a JSON array of objects with fields: type, text.
            Keep the original text verbatim. Do NOT invent content.
            Example: [{"type":"HEADING","text":"Chapter 1"},{"type":"PARAGRAPH","text":"..."}]
            """;

    private final AiProvider aiProvider;
    private final ContentBlockMapper contentBlockMapper;
    private final ObjectMapper objectMapper;

    public AiContentStructureService(AiProvider aiProvider,
                                     ContentBlockMapper contentBlockMapper,
                                     ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.contentBlockMapper = contentBlockMapper;
        this.objectMapper = objectMapper;
    }

    public int structureBlocks(String userSubject, Long spaceId, Long sourceId) {
        List<ContentBlock> blocks = contentBlockMapper.selectBySpaceSourceOwner(spaceId, sourceId, userSubject);
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }

        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            combined.append("BLOCK ").append(i + 1).append(":\n");
            String text = blocks.get(i).getNormalizedText();
            combined.append(text != null ? text : "");
            combined.append("\n\n");
        }

        String prompt = PROMPT + "\n\nDocument:\n" + truncate(combined.toString(), 12000);

        try {
            AiChatResponse response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.user(prompt)),
                    0.0,
                    4000
            ), userSubject);

            String json = response.content().trim();
            json = stripFences(json);
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) {
                return 0;
            }

            List<ContentBlock> toUpdate = new ArrayList<>();
            int limit = Math.min(arr.size(), blocks.size());
            for (int i = 0; i < limit; i++) {
                JsonNode node = arr.get(i);
                String type = node.path("type").asText("");
                String text = node.path("text").asText("");
                if (type.isEmpty() || text.isEmpty()) {
                    continue;
                }
                ContentBlock block = blocks.get(i);
                block.setBlockType(type.toUpperCase());
                block.setStructuredDataJson(node.toString());
                toUpdate.add(block);
            }

            for (ContentBlock block : toUpdate) {
                contentBlockMapper.updateById(block);
            }

            log.info("AI structured {} blocks for source {}", toUpdate.size(), sourceId);
            return toUpdate.size();
        } catch (Exception e) {
            log.error("AI content structure failed for source {}: {}", sourceId, e.getMessage());
            return 0;
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
