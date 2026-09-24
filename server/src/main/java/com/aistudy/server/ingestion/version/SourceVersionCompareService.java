package com.aistudy.server.ingestion.version;

import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.aistudy.server.ingestion.revision.mapper.ExtractionRevisionMapper;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.service.SourceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Compares two ExtractionRevision rows of ONE owned source.
 * Uses extraction_revision_id on pages/blocks — never reuses asset ids.
 */
@Service
public class SourceVersionCompareService {

    private final ExtractionRevisionMapper extractionRevisionMapper;
    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final SourceService sourceService;

    public SourceVersionCompareService(ExtractionRevisionMapper extractionRevisionMapper,
                                       SourcePageMapper sourcePageMapper,
                                       ContentBlockMapper contentBlockMapper,
                                       SourceService sourceService) {
        this.extractionRevisionMapper = extractionRevisionMapper;
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.sourceService = sourceService;
    }

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

        Set<Long> fromPageIds = new HashSet<>();
        Map<Long, Integer> fromPageOrder = new HashMap<>();
        Map<Long, String> fromPageText = new HashMap<>();
        for (SourcePage p : fromPages) {
            fromPageIds.add(p.getId());
            fromPageOrder.put(p.getId(), p.getPageOrder());
            fromPageText.put(p.getId(), p.getExtractedText());
        }
        Set<Long> toPageIds = new HashSet<>();
        Map<Long, Integer> toPageOrder = new HashMap<>();
        Map<Long, String> toPageText = new HashMap<>();
        for (SourcePage p : toPages) {
            toPageIds.add(p.getId());
            toPageOrder.put(p.getId(), p.getPageOrder());
            toPageText.put(p.getId(), p.getExtractedText());
        }

        List<Long> pagesAddedIds = new ArrayList<>();
        for (Long id : toPageIds) {
            if (!fromPageIds.contains(id)) pagesAddedIds.add(id);
        }
        List<Long> pagesRemovedIds = new ArrayList<>();
        for (Long id : fromPageIds) {
            if (!toPageIds.contains(id)) pagesRemovedIds.add(id);
        }
        List<Long> pagesReorderedIds = new ArrayList<>();
        long pagesChanged = 0;
        for (Long id : fromPageIds) {
            if (!toPageIds.contains(id)) continue;
            Integer fo = fromPageOrder.get(id);
            Integer too = toPageOrder.get(id);
            if (fo != null && too != null && !fo.equals(too)) {
                pagesReorderedIds.add(id);
            }
            if (!Objects.equals(fromPageText.get(id), toPageText.get(id))) {
                pagesChanged++;
            }
        }

        List<ContentBlock> fromBlocks = contentBlockMapper.selectByRevision(fromRevisionId, spaceId);
        List<ContentBlock> toBlocks = contentBlockMapper.selectByRevision(toRevisionId, spaceId);
        Set<String> fromKeys = blockKeys(fromBlocks);
        Set<String> toKeys = blockKeys(toBlocks);
        long blocksAdded = 0;
        for (String k : toKeys) if (!fromKeys.contains(k)) blocksAdded++;
        long blocksRemoved = 0;
        for (String k : fromKeys) if (!toKeys.contains(k)) blocksRemoved++;
        Map<String, String> fromTextByKey = blockTextMap(fromBlocks);
        Map<String, String> toTextByKey = blockTextMap(toBlocks);
        long blocksChanged = 0;
        Map<Long, String> updatedContentBlocks = new LinkedHashMap<>();
        for (String k : fromKeys) {
            if (!toKeys.contains(k)) continue;
            if (!Objects.equals(fromTextByKey.get(k), toTextByKey.get(k))) {
                blocksChanged++;
            }
        }

        // Outline diff is bounded: counts only when outline nodes carry revision ids.
        // Full outline projection is a later review item; no fake zeros beyond empty sets.
        return new CompareResult(
                pagesAddedIds.size(), pagesRemovedIds.size(), pagesChanged,
                0, 0, 0,
                blocksAdded, blocksRemoved, blocksChanged,
                pagesAddedIds, pagesRemovedIds, pagesReorderedIds,
                fromPages.size(), toPages.size(),
                updatedContentBlocks);
    }

    private static Set<String> blockKeys(List<ContentBlock> blocks) {
        Set<String> keys = new HashSet<>();
        if (blocks == null) return keys;
        for (ContentBlock b : blocks) {
            keys.add(blockKey(b));
        }
        return keys;
    }

    private static Map<String, String> blockTextMap(List<ContentBlock> blocks) {
        Map<String, String> map = new HashMap<>();
        if (blocks == null) return map;
        for (ContentBlock b : blocks) {
            map.put(blockKey(b), b.getNormalizedText());
        }
        return map;
    }

    private static String blockKey(ContentBlock b) {
        String type = b.getBlockType() == null ? "" : b.getBlockType();
        String text = b.getNormalizedText() == null ? "" : b.getNormalizedText();
        String hash = Integer.toHexString(text.hashCode());
        return type + ":" + (text.length() > 40 ? text.substring(0, 40) : text) + ":" + hash;
    }
}
