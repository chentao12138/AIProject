package com.aistudy.server.ai.content;

import com.aistudy.server.ai.provider.AiChatMessage;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.service.AiUsageRecordService;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.content.service.PublishedRevisionRule;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Classifies already-extracted blocks into structural types (HEADING,
 * PARAGRAPH, LIST, TABLE, CODE) as the AI_PROCESSING stage of a generation job.
 *
 * <h3>Why published content is never rewritten here (audit F-18)</h3>
 *
 * <p>Search and AI answers serve a published source's
 * {@code current_extraction_revision_id} rows, and provenance cites those rows
 * by id. Writing {@code blockType}/{@code structuredDataJson} onto such a row
 * would change what learners are already citing and what
 * {@code knowledge_point_source} points at, with no new revision and no review.
 * So the run refuses when any target row is live published content
 * ({@link PublishedRevisionRule}, the same rule the admin block edit path
 * applies): live material is corrected by re-extracting into a new revision,
 * which review then publishes.
 *
 * <h3>Why the text must come back verbatim</h3>
 *
 * <p>The prompt asks the model to classify without inventing content. A
 * response whose {@code text} differs from the stored block text is refused
 * rather than written, because storing it would silently edit the teaching
 * material under the name of structuring it.
 */
@Service
public class AiContentStructureService {

    private static final Logger log = LoggerFactory.getLogger(AiContentStructureService.class);

    /** The only block types this stage may assign; matches the ContentBlock vocabulary. */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("HEADING", "PARAGRAPH", "LIST", "TABLE", "CODE");

    private static final String PROMPT = """
            Classify each text block into exactly one type: HEADING, PARAGRAPH, LIST, TABLE, CODE.
            Return a JSON array of objects with fields: type, text.
            Keep the original text verbatim. Do NOT invent content.
            Example: [{"type":"HEADING","text":"Chapter 1"},{"type":"PARAGRAPH","text":"..."}]
            """;

    private final AiProvider aiProvider;
    private final ContentBlockMapper contentBlockMapper;
    private final SourceService sourceService;
    private final AiUsageRecordService aiUsageRecordService;
    private final ObjectMapper objectMapper;

    public AiContentStructureService(AiProvider aiProvider,
                                    ContentBlockMapper contentBlockMapper,
                                    SourceService sourceService,
                                    AiUsageRecordService aiUsageRecordService,
                                    ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.contentBlockMapper = contentBlockMapper;
        this.sourceService = sourceService;
        this.aiUsageRecordService = aiUsageRecordService;
        this.objectMapper = objectMapper;
    }

    /**
     * Structures the caller's own source and returns how many blocks changed.
     *
     * <p>Nothing is written unless the whole provider array could be parsed and
     * every entry matches its stored block, so a half-usable response cannot
     * leave part of a document re-typed. Failures throw instead of returning
     * {@code 0}: the worker records the reason, whereas a swallowed zero looked
     * like "nothing to do".
     *
     * @param userSubject JWT subject; the source must belong to this user's space
     * @param spaceId     space of the job
     * @param sourceId    source whose blocks are classified
     * @return the number of blocks updated
     * @throws IllegalStateException when the source is not the caller's, has no
     *         blocks, is published (live content may not be re-written in
     *         place), or the provider response is unusable or alters text
     */
    @Transactional
    public int structureBlocks(String userSubject, Long spaceId, Long sourceId) {
        Source source = sourceService.getMine(userSubject, spaceId, sourceId);
        if (source == null) {
            throw new IllegalStateException(
                    "source is not in this space or does not exist");
        }
        List<ContentBlock> blocks =
                contentBlockMapper.selectBySpaceSourceOwner(spaceId, sourceId, userSubject);
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalStateException(
                    "source has no content blocks to structure");
        }
        for (ContentBlock block : blocks) {
            if (PublishedRevisionRule.isLivePublishedContent(source, block.getExtractionRevisionId())) {
                throw new IllegalStateException(
                        "source content is published; re-extract into a new revision"
                                + " instead of structuring live content in place");
            }
        }

        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            combined.append("BLOCK ").append(i + 1).append(":\n");
            String text = blocks.get(i).getNormalizedText();
            combined.append(text != null ? text : "");
            combined.append("\n\n");
        }
        String prompt = PROMPT + "\n\nDocument:\n" + truncate(combined.toString(), 12000);

        JsonNode arr;
        AiChatResponse response = null;
        long startedNanos = System.nanoTime();
        try {
            response = aiProvider.chat(new AiChatRequest(
                    List.of(AiChatMessage.user(prompt)),
                    0.0,
                    4000
            ), userSubject);
            arr = objectMapper.readTree(stripFences(response.content().trim()));
        } catch (Exception e) {
            aiUsageRecordService.recordProviderCall(userSubject, spaceId,
                    AiUsageRecordService.PURPOSE_CONTENT_STRUCTURE, response,
                    AiUsageRecordService.elapsedMs(startedNanos),
                    response == null ? "AI_PROVIDER_CALL_FAILED" : "AI_RESPONSE_INVALID");
            throw new IllegalStateException(
                    "AI content structuring failed: " + bounded(e.getMessage()), e);
        }
        aiUsageRecordService.recordProviderCall(userSubject, spaceId,
                AiUsageRecordService.PURPOSE_CONTENT_STRUCTURE, response,
                AiUsageRecordService.elapsedMs(startedNanos), null);

        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException(
                    "AI content structuring returned no array to classify");
        }
        // Model output may describe fewer entries than the document has blocks;
        // that is a partial classification, not a failure, so the pairs are
        // validated first and applied only if every pair is trustworthy.
        int limit = Math.min(arr.size(), blocks.size());
        List<ContentBlock> validated = new ArrayList<>();
        List<String[]> updates = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            JsonNode node = arr.get(i);
            String type = node.path("type").asText("").toUpperCase();
            String text = node.path("text").asText("");
            if (type.isEmpty() || text.isEmpty()) {
                throw new IllegalStateException(
                        "AI content structuring returned an incomplete entry");
            }
            if (!ALLOWED_TYPES.contains(type)) {
                throw new IllegalStateException(
                        "AI content structuring returned an unknown block type: " + type);
            }
            ContentBlock block = blocks.get(i);
            String stored = block.getNormalizedText() == null ? "" : block.getNormalizedText();
            if (!stored.equals(text)) {
                throw new IllegalStateException(
                        "AI content structuring altered block text; classification only");
            }
            validated.add(block);
            updates.add(new String[]{type, node.toString()});
        }
        if (validated.isEmpty()) {
            throw new IllegalStateException(
                    "AI content structuring classified no block");
        }

        for (int i = 0; i < validated.size(); i++) {
            ContentBlock block = validated.get(i);
            block.setBlockType(updates.get(i)[0]);
            block.setStructuredDataJson(updates.get(i)[1]);
            contentBlockMapper.updateById(block);
        }

        log.info("AI structured {} blocks of source {} (revision {})",
                validated.size(), sourceId, source.getCurrentExtractionRevisionId());
        return validated.size();
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
        if (text == null) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
    }

    /** Keeps a provider/JSON error message short enough to store safely. */
    private static String bounded(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
