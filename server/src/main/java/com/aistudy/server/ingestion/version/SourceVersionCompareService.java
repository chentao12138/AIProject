package com.aistudy.server.ingestion.version;

import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.aistudy.server.ingestion.revision.mapper.ExtractionRevisionMapper;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.outline.entity.SourceOutlineNode;
import com.aistudy.server.source.outline.service.SourceOutlineNodeService;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.service.SourceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Compares two ExtractionRevision rows of ONE owned source.
 *
 * <p>Each revision owns its own page, block and outline rows, so two revisions
 * of the same document never share a primary key. Correspondence therefore runs
 * on document identity — page number for pages, page plus position for blocks,
 * title path for outline nodes — and never on the database id. Keying by id made
 * every page of a re-extraction read as removed plus added, which is what audit
 * F-11 reported.
 */
@Service
public class SourceVersionCompareService {

    /**
     * Cap on the per-block detail map. The counts stay exact; only the id→text
     * detail is bounded, so re-ingesting a whole book cannot return tens of
     * thousands of text bodies in one response.
     */
    static final int MAX_UPDATED_BLOCK_DETAILS = 200;

    private final ExtractionRevisionMapper extractionRevisionMapper;
    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final SourceOutlineNodeService sourceOutlineNodeService;
    private final SourceService sourceService;

    public SourceVersionCompareService(ExtractionRevisionMapper extractionRevisionMapper,
                                       SourcePageMapper sourcePageMapper,
                                       ContentBlockMapper contentBlockMapper,
                                       SourceOutlineNodeService sourceOutlineNodeService,
                                       SourceService sourceService) {
        this.extractionRevisionMapper = extractionRevisionMapper;
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.sourceOutlineNodeService = sourceOutlineNodeService;
        this.sourceService = sourceService;
    }

    /**
     * @param pagesAddedIds     row ids (newer revision) of pages absent before
     * @param pagesRemovedIds   row ids (older revision) of pages gone now
     * @param pagesReorderedIds row ids (newer revision) whose page order moved
     * @param updatedContentBlocks newer-revision row id → its text for blocks
     *                             whose text or type changed, bounded by
     *                             {@link #MAX_UPDATED_BLOCK_DETAILS}
     */
    public record CompareResult(long pagesAdded, long pagesRemoved, long pagesChanged,
                                long outlineAdded, long outlineRemoved, long outlineChanged,
                                long blocksAdded, long blocksRemoved, long blocksChanged,
                                List<Long> pagesAddedIds, List<Long> pagesRemovedIds,
                                List<Long> pagesReorderedIds,
                                long totalPagesBefore, long totalPagesAfter,
                                Map<Long, String> updatedContentBlocks) {
    }

    public CompareResult compare(String ownerSubject, Long spaceId, Long sourceId,
                                 Long fromRevisionId, Long toRevisionId) {
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        ExtractionRevision from = extractionRevisionMapper.selectByIdSpaceSource(fromRevisionId, spaceId, sourceId);
        ExtractionRevision to = extractionRevisionMapper.selectByIdSpaceSource(toRevisionId, spaceId, sourceId);
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExtractionRevision not found");
        }

        List<SourcePage> fromPages = sourcePageMapper.selectByRevision(fromRevisionId, spaceId);
        List<SourcePage> toPages = sourcePageMapper.selectByRevision(toRevisionId, spaceId);
        Map<String, SourcePage> fromByPageKey = pagesByKey(fromPages);
        Map<String, SourcePage> toByPageKey = pagesByKey(toPages);

        List<Long> pagesAddedIds = new ArrayList<>();
        List<Long> pagesReorderedIds = new ArrayList<>();
        long pagesChanged = 0;
        for (Map.Entry<String, SourcePage> entry : toByPageKey.entrySet()) {
            SourcePage newer = entry.getValue();
            SourcePage older = fromByPageKey.get(entry.getKey());
            if (older == null) {
                pagesAddedIds.add(newer.getId());
                continue;
            }
            if (!Objects.equals(older.getPageOrder(), newer.getPageOrder())) {
                pagesReorderedIds.add(newer.getId());
            }
            if (!Objects.equals(older.getExtractedText(), newer.getExtractedText())) {
                pagesChanged++;
            }
        }
        List<Long> pagesRemovedIds = new ArrayList<>();
        for (Map.Entry<String, SourcePage> entry : fromByPageKey.entrySet()) {
            if (!toByPageKey.containsKey(entry.getKey())) {
                pagesRemovedIds.add(entry.getValue().getId());
            }
        }

        // Blocks are keyed by the page they sit on plus their position on that
        // page, so an edited paragraph reads as one changed block instead of a
        // removal plus an addition — and the changed text can actually be shown.
        Map<String, ContentBlock> fromBlocks = blocksByKey(
                contentBlockMapper.selectByRevision(fromRevisionId, spaceId), pageRowKeys(fromPages));
        Map<String, ContentBlock> toBlocks = blocksByKey(
                contentBlockMapper.selectByRevision(toRevisionId, spaceId), pageRowKeys(toPages));

        long blocksAdded = 0;
        long blocksChanged = 0;
        Map<Long, String> updatedContentBlocks = new LinkedHashMap<>();
        for (Map.Entry<String, ContentBlock> entry : toBlocks.entrySet()) {
            ContentBlock newer = entry.getValue();
            ContentBlock older = fromBlocks.get(entry.getKey());
            if (older == null) {
                blocksAdded++;
                continue;
            }
            if (!Objects.equals(older.getNormalizedText(), newer.getNormalizedText())
                    || !Objects.equals(older.getBlockType(), newer.getBlockType())) {
                blocksChanged++;
                if (updatedContentBlocks.size() < MAX_UPDATED_BLOCK_DETAILS) {
                    updatedContentBlocks.put(newer.getId(), newer.getNormalizedText());
                }
            }
        }
        long blocksRemoved = 0;
        for (String key : fromBlocks.keySet()) {
            if (!toBlocks.containsKey(key)) {
                blocksRemoved++;
            }
        }

        Map<String, SourceOutlineNode> fromOutline =
                outlineByPath(sourceOutlineNodeService.listByRevision(spaceId, fromRevisionId));
        Map<String, SourceOutlineNode> toOutline =
                outlineByPath(sourceOutlineNodeService.listByRevision(spaceId, toRevisionId));
        long outlineAdded = 0;
        long outlineChanged = 0;
        for (Map.Entry<String, SourceOutlineNode> entry : toOutline.entrySet()) {
            SourceOutlineNode older = fromOutline.get(entry.getKey());
            if (older == null) {
                outlineAdded++;
            } else if (outlineSignature(older) != outlineSignature(entry.getValue())) {
                outlineChanged++;
            }
        }
        long outlineRemoved = 0;
        for (String path : fromOutline.keySet()) {
            if (!toOutline.containsKey(path)) {
                outlineRemoved++;
            }
        }

        return new CompareResult(
                pagesAddedIds.size(), pagesRemovedIds.size(), pagesChanged,
                outlineAdded, outlineRemoved, outlineChanged,
                blocksAdded, blocksRemoved, blocksChanged,
                pagesAddedIds, pagesRemovedIds, pagesReorderedIds,
                fromPages.size(), toPages.size(),
                updatedContentBlocks);
    }

    // ==================== identity keys ====================

    /**
     * A page's identity across revisions is its page number. A row with no page
     * number can only be matched by its own id, which reports it as removed plus
     * added rather than silently pairing it with a different page.
     */
    private static String pageKey(SourcePage page) {
        return page.getSourcePageNumber() != null
                ? "n:" + page.getSourcePageNumber()
                : "i:" + page.getId();
    }

    private static Map<String, SourcePage> pagesByKey(List<SourcePage> pages) {
        Map<String, SourcePage> map = new LinkedHashMap<>();
        for (SourcePage page : pages) {
            map.put(pageKey(page), page);
        }
        return map;
    }

    private static Map<Long, String> pageRowKeys(List<SourcePage> pages) {
        Map<Long, String> map = new HashMap<>();
        for (SourcePage page : pages) {
            map.put(page.getId(), pageKey(page));
        }
        return map;
    }

    private static Map<String, ContentBlock> blocksByKey(List<ContentBlock> blocks,
                                                         Map<Long, String> pageRowToKey) {
        Map<String, ContentBlock> map = new LinkedHashMap<>();
        if (blocks == null) {
            return map;
        }
        for (ContentBlock block : blocks) {
            String page = pageRowToKey.getOrDefault(block.getSourcePageId(),
                    "orphan:" + block.getSourcePageId());
            // Unordered blocks fall back to their row id so two of them on one
            // page cannot overwrite each other in the map.
            String position = block.getSortOrder() != null
                    ? "s:" + block.getSortOrder()
                    : "i:" + block.getId();
            map.put(page + "/" + position, block);
        }
        return map;
    }

    /**
     * Outline nodes are keyed by their title path within the revision, because
     * the parent id changes on every re-extraction. Repeated titles under one
     * parent are separated by occurrence order rather than collapsed.
     */
    private static Map<String, SourceOutlineNode> outlineByPath(List<SourceOutlineNode> nodes) {
        Map<Long, SourceOutlineNode> byId = new HashMap<>();
        for (SourceOutlineNode node : nodes) {
            byId.put(node.getId(), node);
        }
        Map<String, SourceOutlineNode> paths = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (SourceOutlineNode node : nodes) {
            String path = pathOf(node, byId);
            int seen = occurrences.merge(path, 1, Integer::sum) - 1;
            paths.put(seen == 0 ? path : path + "#" + seen, node);
        }
        return paths;
    }

    private static String pathOf(SourceOutlineNode node, Map<Long, SourceOutlineNode> byId) {
        Deque<String> segments = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        SourceOutlineNode current = node;
        while (current != null && visited.add(current.getId())) {
            segments.addFirst(labelOf(current));
            current = current.getParentId() == null ? null : byId.get(current.getParentId());
        }
        return String.join(">", segments);
    }

    private static String labelOf(SourceOutlineNode node) {
        String number = node.getNumberLabel() == null ? "" : node.getNumberLabel() + " ";
        String title = node.getTitle() == null ? "" : node.getTitle().trim();
        return number + title;
    }

    /** Page span, node type and position are the parts a reviewer acts on. */
    private static int outlineSignature(SourceOutlineNode node) {
        return Objects.hash(node.getNodeType(), node.getStartPage(), node.getEndPage(),
                node.getSortOrder());
    }
}
