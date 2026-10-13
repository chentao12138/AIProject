package com.aistudy.server.source.page.service;

import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.page.dto.ReorderPagesRequest;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.service.SourceService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SourcePage application service: owner-scoped reads, human reorder, and the
 * one place that owns the Source-wide page-order invariant (F-31).
 *
 * <h3>The invariant</h3>
 *
 * <p>For one Source, {@code page_order} is a conflict-free numbering of the
 * pages the owner actually sees (the same set {@link #listMine} returns):
 * distinct per page, positive, and — after a reorder — exactly the
 * contiguous range {@code 1..N}. Two operations maintain it:
 * <ul>
 *   <li>{@link #allocateOrderSlots} hands ingestion the lowest free slots, so
 *       multi-asset Sources get continuation orders (1,2,3 …) instead of every
 *       image restarting at 1, and a re-extraction reuses the slots its own
 *       pages freed rather than pushing a human-confirmed page further down.</li>
 *   <li>{@link #batchUpdateOrder} renumbers the <em>whole</em> visible set, so
 *       unlisted pages can never end up sharing an order with a newly assigned
 *       one (the reported bug: validating only the submitted rows left the
 *       other pages where they were).</li>
 * </ul>
 *
 * <p>Cross-source / unknown ids fail the whole request with no partial update;
 * the reorder is one transaction.
 *
 * <h3>Known boundary</h3>
 *
 * <p>Slot allocation is a read-then-write and is NOT atomic against a second
 * concurrent ingestion on the same Source; the job claim lease is what keeps
 * that from happening in practice. {@code docs/operations-ocr-docker.md}
 * records why the matching unique index is deliberately deferred.
 */
@Service
public class SourcePageService {

    /** Maximum pages one reorder request may name; bounds the work per transaction. */
    private static final int MAX_REORDER_PAGES = 2000;

    private final SourcePageMapper sourcePageMapper;
    private final SourceService sourceService;

    public SourcePageService(SourcePageMapper sourcePageMapper,
                             SourceService sourceService) {
        this.sourcePageMapper = sourcePageMapper;
        this.sourceService = sourceService;
    }

    /**
     * One page of the caller's space, or {@code null} when it does not exist or
     * belongs to another space. Exposed so a reader that only has a page id
     * (AI context chips, note context links) does not have to import this
     * module's mapper (architecture.md §4).
     *
     * <p>The caller must already have proved it owns {@code spaceId}; the page
     * row itself is checked against that space here.
     */
    /** Low-confidence pages of one revision, in page order. Audit F-06. */
    public List<SourcePage> listLowOrderConfidence(Long spaceId, Long revisionId, double threshold) {
        return sourcePageMapper.selectLowOrderConfidenceByRevision(spaceId, revisionId, threshold);
    }

    public SourcePage getMine(String ownerSubject, Long spaceId, Long pageId) {
        if (pageId == null || spaceId == null) {
            return null;
        }
        return sourcePageMapper.selectByIdAndSpace(pageId, spaceId);
    }

    public List<SourcePage> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            return null;
        }
        return sourcePageMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

    /**
     * Allocates the next page orders for one asset of a Source (F-31).
     *
     * <p>Rule: the {@code count} lowest positive integers that no current page
     * of this Source already uses. For the first asset of an empty Source that
     * is {@code 1..count}; for the second asset it continues after the first;
     * for a re-extraction the asset's own rows were just deleted, so its slots
     * are handed straight back and the pages around it do not move.
     *
     * @param spaceId  owning space (validated by the caller's asset)
     * @param sourceId source whose pages define the used set
     * @param count    pages to place, {@code >= 1}
     * @return ascending distinct orders, exactly {@code count} values
     * @throws IllegalArgumentException when {@code count} is below 1 or so large
     *         that the numbering would overflow the INT column
     */
    @Transactional(readOnly = true)
    public int[] allocateOrderSlots(Long spaceId, Long sourceId, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }
        if (count > 100_000) {
            throw new IllegalArgumentException("count is implausibly large: " + count);
        }
        Set<Integer> used = new HashSet<>();
        // Column names rather than lambdas: the read is a plain "which orders are
        // already taken" lookup, and it stays usable outside a MyBatis-Plus
        // bootstrap (the lambda cache is only populated per registered mapper).
        List<SourcePage> existing = sourcePageMapper.selectList(new QueryWrapper<SourcePage>()
                .select("id", "page_order")
                .eq("space_id", spaceId)
                .eq("source_id", sourceId)
                .orderByAsc("page_order", "id"));
        if (existing != null) {
            for (SourcePage page : existing) {
                if (page.getPageOrder() != null && page.getPageOrder() > 0) {
                    used.add(page.getPageOrder());
                }
            }
        }
        int[] slots = new int[count];
        int candidate = 1;
        for (int i = 0; i < count; i++) {
            while (used.contains(candidate)) {
                candidate++;
            }
            slots[i] = candidate;
            used.add(candidate);
            candidate++;
        }
        return slots;
    }

    /**
     * Applies a human reorder and leaves the Source in a conflict-free state.
     *
     * <h3>Request semantics (partial batches are allowed)</h3>
     *
     * <ul>
     *   <li>Every named {@code pageId} must belong to this Source and owner;
     *       anything else is a 400 that changes nothing.</li>
     *   <li>{@code pageOrder} must be distinct across the request and within
     *       {@code 1..N} where {@code N} is the size of the Source's visible
     *       page set. A duplicate, a zero, an out-of-range value or a repeated
     *       id is a 400.</li>
     *   <li>An item with {@code pageOrder = null} says "this page takes part in
     *       the reorder but has no explicit slot".</li>
     *   <li>Pages that are not named at all keep their relative order.</li>
     *   <li>The result is always the full permutation {@code 1..N}: explicitly
     *       ordered pages take their requested slots, and the remaining pages
     *       (unlisted plus those without an explicit order) fill the free slots
     *       in ascending order of their previous position. This deterministic
     *       renumbering — not a reject — is the documented rule for partial
     *       batches, because rejecting them would break existing clients while
     *       silently accepting them is what produced the duplicates.</li>
     *   <li>Named pages are marked {@code CONFIRMED}; pages only moved by the
     *       renumbering keep their own order status so an earlier human
     *       confirmation is not erased by a neighbour's edit.</li>
     * </ul>
     *
     * @param ownerSubject authenticated owner; the space must be theirs
     * @param spaceId      path space id
     * @param sourceId     path source id, the ownership source of truth
     * @param request      typed reorder body
     * @throws ResponseStatusException 404 unknown source; 400 invalid body,
     *         unknown/cross-source pageId, duplicate id/order, an order outside
     *         {@code 1..N}, or a batch larger than {@value #MAX_REORDER_PAGES}
     *         pages
     */
    @Transactional
    public void batchUpdateOrder(String ownerSubject, Long spaceId, Long sourceId,
                                 ReorderPagesRequest request) {
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        if (request == null || request.pages() == null || request.pages().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pages must not be empty");
        }
        if (request.pages().size() > MAX_REORDER_PAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "a reorder request may name at most " + MAX_REORDER_PAGES + " pages");
        }

        List<SourcePage> visible = sourcePageMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
        if (visible == null || visible.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        Map<Long, SourcePage> byId = new HashMap<>();
        for (SourcePage page : visible) {
            byId.put(page.getId(), page);
        }
        int total = byId.size();

        Set<Long> seenIds = new HashSet<>();
        Set<Integer> seenOrders = new HashSet<>();
        Map<Long, Integer> requested = new HashMap<>();
        Map<Long, ReorderPagesRequest.ReorderPageItem> itemsById = new HashMap<>();
        for (ReorderPagesRequest.ReorderPageItem item : request.pages()) {
            if (item == null || item.pageId() == null || !byId.containsKey(item.pageId())) {
                // Cross-source or unknown pageId → fail entire request.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "pageId does not belong to this source");
            }
            if (!seenIds.add(item.pageId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate pageId");
            }
            itemsById.put(item.pageId(), item);
            if (item.pageOrder() == null) {
                continue;
            }
            int order = item.pageOrder();
            // 1..N, not merely ">= 1": an order past the end of the set used to
            // be accepted and then collide with the unlisted pages it jumped.
            if (order < 1 || order > total || !seenOrders.add(order)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "invalid or duplicate pageOrder, expected distinct values in 1.." + total);
            }
            requested.put(item.pageId(), order);
        }

        // Unlisted pages and explicitly-flexible pages share the leftover slots,
        // in ascending order of where they were before this request.
        List<SourcePage> fillers = new ArrayList<>();
        Set<Long> fixedIds = requested.keySet();
        for (SourcePage page : visible) {
            if (!fixedIds.contains(page.getId())) {
                fillers.add(page);
            }
        }
        fillers.sort(Comparator
                .comparing((SourcePage p) -> p.getPageOrder() == null ? Integer.MAX_VALUE : p.getPageOrder())
                .thenComparing(SourcePage::getId));
        TreeSet<Integer> freeSlots = new TreeSet<>();
        for (int i = 1; i <= total; i++) {
            if (!seenOrders.contains(i)) {
                freeSlots.add(i);
            }
        }
        Map<Long, Integer> finalOrders = new HashMap<>();
        requested.forEach(finalOrders::put);
        for (SourcePage page : fillers) {
            Integer slot = freeSlots.pollFirst();
            if (slot == null) {
                // Unreachable: slots are exactly total - requested.size() and so
                // are the fillers. Kept as a guard so a future edit cannot write
                // a partial permutation.
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "reorder planning ran out of slots");
            }
            finalOrders.put(page.getId(), slot);
        }

        LocalDateTime now = LocalDateTime.now();
        // Two-phase write: a direct 1..N assignment would transiently put two
        // pages in the same slot, which a unique index (or a reader between the
        // statements) must never see. Negatives are outside the legal range, so
        // parking every row there first is collision-free by construction.
        int park = 0;
        for (SourcePage page : visible) {
            park++;
            int updated = sourcePageMapper.updateOrderByIdSpaceSource(page.getId(), spaceId, sourceId,
                    -park, page.getPageType(), now);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "pageId does not belong to this source");
            }
        }
        for (SourcePage page : visible) {
            int order = finalOrders.get(page.getId());
            String pageType = page.getPageType();
            ReorderPagesRequest.ReorderPageItem item = itemsById.get(page.getId());
            if (item != null && item.pageType() != null && !item.pageType().isBlank()) {
                pageType = item.pageType();
            }
            int updated = sourcePageMapper.updateOrderByIdSpaceSource(page.getId(), spaceId, sourceId,
                    order, pageType, now);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "pageId does not belong to this source");
            }
            if (item != null && item.printedPageNumber() != null) {
                SourcePage patch = new SourcePage();
                patch.setId(page.getId());
                patch.setPrintedPageNumber(item.printedPageNumber());
                patch.setUpdatedAt(now);
                sourcePageMapper.updateById(patch);
            }
        }
        // Only the pages a human explicitly placed become CONFIRMED: an auto
        // page that merely shifted to make room is not a human decision.
        for (Long pageId : seenIds) {
            if (requested.containsKey(pageId)) {
                sourcePageMapper.confirmOrderByIdSpaceSource(pageId, spaceId, sourceId, now);
            }
        }
    }
}
