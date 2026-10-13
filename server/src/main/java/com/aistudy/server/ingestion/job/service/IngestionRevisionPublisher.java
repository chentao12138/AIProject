package com.aistudy.server.ingestion.job.service;

import com.aistudy.server.ingestion.revision.mapper.ExtractionRevisionMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one atomic step that makes a freshly extracted revision visible.
 *
 * <p>Stamps the revision onto the pages and blocks, moves the revision to
 * NEEDS_REVIEW, points {@code source.current_extraction_revision_id} at it, and
 * carries a human's confirmed page order into the new revision. These writes used
 * to run as separate autocommit statements from the worker thread, so a crash
 * between them could leave the source's current revision pointing at a DRAFT
 * whose pages were never stamped.
 *
 * <p>The carry-over lives here rather than in {@code SourcePageService} on
 * purpose: the page service depends on {@code SourceService}, whose extraction
 * gateway depends back on this publisher, so injecting it would close a bean
 * cycle. Only the page module's own scoped statements are used, and the rules
 * they enforce (same space AND same source per write) are kept.
 */
@Service
public class IngestionRevisionPublisher {

    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final ExtractionRevisionMapper extractionRevisionMapper;
    private final SourceMapper sourceMapper;

    public IngestionRevisionPublisher(SourcePageMapper sourcePageMapper,
                                      ContentBlockMapper contentBlockMapper,
                                      ExtractionRevisionMapper extractionRevisionMapper,
                                      SourceMapper sourceMapper) {
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.extractionRevisionMapper = extractionRevisionMapper;
        this.sourceMapper = sourceMapper;
    }

    @Transactional
    public void publish(Long spaceId, Long sourceId, Long revisionId, LocalDateTime at) {
        Source before = sourceMapper.selectById(sourceId);
        Long previousRevisionId = before == null ? null : before.getCurrentExtractionRevisionId();

        sourcePageMapper.stampRevisionOnSourcePages(spaceId, sourceId, revisionId, at);
        contentBlockMapper.stampRevisionOnSourceBlocks(spaceId, sourceId, revisionId, at);
        extractionRevisionMapper.updateStatus(revisionId, spaceId, sourceId, "NEEDS_REVIEW", null);
        sourceMapper.updateCurrentRevision(sourceId, spaceId, revisionId, at);

        carryOverConfirmedOrder(spaceId, sourceId, previousRevisionId, revisionId, at);
    }

    /**
     * Keeps an operator's confirmed reading order across a re-extraction
     * (audit F-31). Re-extraction produces fresh page rows, so before this the
     * new revision silently fell back to whatever order the extractor guessed
     * and the human decision was lost.
     *
     * <p>Matching is by {@code source_page_number}, the document identity of a
     * page. A confirmed anchor that no longer fits the new page count (the
     * re-run found more or fewer pages) is dropped rather than forcing a
     * duplicate slot, and every remaining page gets one of the free slots in its
     * previous relative order, so the revision always ends up a clean 1..N
     * permutation.
     *
     * @return pages that inherited a confirmed position
     */
    private int carryOverConfirmedOrder(Long spaceId, Long sourceId, Long fromRevisionId,
                                        Long toRevisionId, LocalDateTime at) {
        if (fromRevisionId == null || toRevisionId == null || fromRevisionId.equals(toRevisionId)) {
            return 0;
        }
        Map<Integer, Integer> confirmedByPageNumber = new HashMap<>();
        for (SourcePage page : sourcePageMapper.selectByRevision(fromRevisionId, spaceId)) {
            if ("CONFIRMED".equals(page.getOrderStatus())
                    && page.getSourcePageNumber() != null && page.getPageOrder() != null) {
                confirmedByPageNumber.putIfAbsent(page.getSourcePageNumber(), page.getPageOrder());
            }
        }
        if (confirmedByPageNumber.isEmpty()) {
            return 0;
        }
        List<SourcePage> current = new ArrayList<>(sourcePageMapper.selectByRevision(toRevisionId, spaceId));
        if (current.isEmpty()) {
            return 0;
        }
        current.sort(Comparator.comparing(
                (SourcePage p) -> p.getPageOrder() == null ? Integer.MAX_VALUE : p.getPageOrder())
                .thenComparing(SourcePage::getId));

        int total = current.size();
        Map<Long, Integer> finalOrders = new HashMap<>();
        Set<Integer> taken = new HashSet<>();
        List<SourcePage> flexible = new ArrayList<>();
        int carried = 0;
        for (SourcePage page : current) {
            Integer anchor = page.getSourcePageNumber() == null
                    ? null : confirmedByPageNumber.get(page.getSourcePageNumber());
            if (anchor != null && anchor >= 1 && anchor <= total && taken.add(anchor)) {
                finalOrders.put(page.getId(), anchor);
                carried++;
            } else {
                flexible.add(page);
            }
        }
        if (carried == 0) {
            return 0;
        }
        TreeSet<Integer> freeSlots = new TreeSet<>();
        for (int slot = 1; slot <= total; slot++) {
            if (!taken.contains(slot)) {
                freeSlots.add(slot);
            }
        }
        for (SourcePage page : flexible) {
            Integer slot = freeSlots.pollFirst();
            if (slot == null) {
                // Unreachable: anchors are distinct and inside 1..total, so the
                // free slots are exactly as numerous as the flexible pages.
                break;
            }
            finalOrders.put(page.getId(), slot);
        }

        // Two-phase write, same reason as SourcePageService.batchUpdateOrder: a
        // direct assignment would transiently park two rows in one slot.
        int park = 0;
        for (SourcePage page : current) {
            park++;
            sourcePageMapper.updateOrderByIdSpaceSource(page.getId(), spaceId, sourceId, -park,
                    page.getPageType(), at);
        }
        for (SourcePage page : current) {
            Integer order = finalOrders.get(page.getId());
            if (order == null) {
                continue;
            }
            sourcePageMapper.updateOrderByIdSpaceSource(page.getId(), spaceId, sourceId, order,
                    page.getPageType(), at);
            if (confirmedByPageNumber.containsKey(page.getSourcePageNumber())
                    && order.equals(confirmedByPageNumber.get(page.getSourcePageNumber()))) {
                sourcePageMapper.confirmOrderByIdSpaceSource(page.getId(), spaceId, sourceId, at);
            }
        }
        return carried;
    }
}
