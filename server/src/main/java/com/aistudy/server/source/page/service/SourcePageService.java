package com.aistudy.server.source.page.service;

import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.page.dto.ReorderPagesRequest;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.service.SourceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SourcePage application service.
 *
 * <p>Reorder is all-or-nothing: every pageId must belong to the path
 * source (and owner+space). Cross-source ids fail the whole request
 * with no partial update.
 */
@Service
public class SourcePageService {

    private final SourcePageMapper sourcePageMapper;
    private final SourceService sourceService;

    public SourcePageService(SourcePageMapper sourcePageMapper,
                             SourceService sourceService) {
        this.sourcePageMapper = sourcePageMapper;
        this.sourceService = sourceService;
    }

    public List<SourcePage> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            return null;
        }
        return sourcePageMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

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

        List<SourcePage> existing = sourcePageMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        Set<Long> validIds = new HashSet<>();
        for (SourcePage p : existing) {
            validIds.add(p.getId());
        }

        Set<Long> seenIds = new HashSet<>();
        Set<Integer> seenOrders = new HashSet<>();
        for (ReorderPagesRequest.ReorderPageItem item : request.pages()) {
            if (item.pageId() == null || !validIds.contains(item.pageId())) {
                // Cross-source or unknown pageId → fail entire request.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "pageId does not belong to this source");
            }
            if (!seenIds.add(item.pageId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate pageId");
            }
            int order = item.pageOrder() != null ? item.pageOrder() : (seenIds.size());
            if (order < 1 || !seenOrders.add(order)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid or duplicate pageOrder");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int fallback = 0;
        for (ReorderPagesRequest.ReorderPageItem item : request.pages()) {
            fallback++;
            int order = item.pageOrder() != null ? item.pageOrder() : fallback;
            int updated = sourcePageMapper.updateOrderByIdSpaceSource(
                    item.pageId(), spaceId, sourceId, order, item.pageType(), now);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "pageId does not belong to this source");
            }
        }
        // Confirm every successfully reordered page of this request.
        for (ReorderPagesRequest.ReorderPageItem item : request.pages()) {
            sourcePageMapper.confirmOrderByIdSpaceSource(item.pageId(), spaceId, sourceId, now);
        }
    }
}
