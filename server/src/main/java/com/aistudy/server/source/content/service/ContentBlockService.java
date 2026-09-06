package com.aistudy.server.source.content.service;

import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BUSINESS-006 — read-side application service for {@link ContentBlock}
 * (extraction writes belong to
 * {@link com.aistudy.server.ingestion.extract.ContentExtractionService}).
 *
 * <p>Owner/space scoping follows the BUSINESS-002/004 pattern: the
 * parent source is validated via {@link SourceService#getMine} (null
 * → 404) and the list query itself is owner-scoped in SQL. The
 * optional {@code pageId} filter is also owner-scoped: a page id from
 * another source simply yields no rows (it is a query filter, not a
 * resource path — an empty result leaks nothing).
 */
@Service
public class ContentBlockService {

    private final ContentBlockMapper contentBlockMapper;
    private final SourceService sourceService;

    public ContentBlockService(ContentBlockMapper contentBlockMapper,
                               SourceService sourceService) {
        this.contentBlockMapper = contentBlockMapper;
        this.sourceService = sourceService;
    }

    /**
     * Lists the blocks of the caller's own source in document order,
     * optionally scoped to one page of that source.
     *
     * @return blocks (possibly empty), or {@code null} when the
     *         source is absent / not owned (404)
     */
    public List<ContentBlock> listMine(String ownerSubject,
                                       Long spaceId,
                                       Long sourceId,
                                       Long pageId) {
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            return null;
        }
        if (pageId != null) {
            return contentBlockMapper.selectBySpaceSourcePageOwner(
                    spaceId, sourceId, pageId, ownerSubject);
        }
        return contentBlockMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

    /**
     * Returns ONE block of the caller's own space (owner-scoped by
     * id + spaceId, source↔space consistency in SQL). Used by the
     * provenance slice to validate a ContentBlock endpoint without
     * knowing its source id.
     *
     * @return the block, or {@code null} when absent / not owned /
     *         cross-space (404)
     */
    public ContentBlock getMine(String ownerSubject, Long spaceId, Long blockId) {
        return contentBlockMapper.selectByIdSpaceOwner(blockId, spaceId, ownerSubject);
    }
}
