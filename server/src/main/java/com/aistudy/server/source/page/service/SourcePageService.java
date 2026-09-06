package com.aistudy.server.source.page.service;

import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.service.SourceService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BUSINESS-006 — read-side application service for {@link SourcePage}
 * (extraction writes belong to
 * {@link com.aistudy.server.ingestion.extract.ContentExtractionService}).
 *
 * <p>Owner/space scoping follows the BUSINESS-002/004 pattern: the
 * parent source is validated via {@link SourceService#getMine} (null
 * → 404) and the list query itself is owner-scoped in SQL.
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

    /**
     * Lists the pages of the caller's own source in final reading
     * order.
     *
     * @return pages (possibly empty), or {@code null} when the
     *         source is absent / not owned (404)
     */
    public List<SourcePage> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            return null;
        }
        return sourcePageMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }
}
