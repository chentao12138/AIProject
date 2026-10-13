package com.aistudy.server.source.content.service;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.source.content.dto.UpdateContentBlockRequest;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-006 — application service for extracted ContentBlock rows.
 *
 * <p>Ownership is always proven through the parent chain
 * (space → source → page → block) rather than from the block row alone, and
 * every write states the source it is allowed to touch: a block id that is
 * real but belongs to a different source of the same space is answered with
 * 404, the same shape as "does not exist", so ids leak nothing (F-26).
 */
@Service
public class ContentBlockService {

    /** Stable code (409): the row is the content a PUBLISHED source is serving. */
    public static final String ERROR_PUBLISHED_REVISION_READONLY =
            "CONTENT_BLOCK_PUBLISHED_REVISION_READONLY";

    private final ContentBlockMapper contentBlockMapper;
    private final SourceService sourceService;

    public ContentBlockService(ContentBlockMapper contentBlockMapper,
                               SourceService sourceService) {
        this.contentBlockMapper = contentBlockMapper;
        this.sourceService = sourceService;
    }

    /**
     * Blocks of the caller's own source, document ordered, optionally scoped to
     * one page.
     *
     * @return {@code null} when the source is absent or not the caller's (the
     *         controller turns that into 404); an empty list is a valid 200
     */
    public List<ContentBlock> listMine(String ownerSubject, Long spaceId, Long sourceId, Long pageId) {
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        if (pageId != null) {
            return contentBlockMapper.selectBySpaceSourcePageOwner(spaceId, sourceId, pageId, ownerSubject);
        }
        return contentBlockMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

    /**
     * One block addressed by space and owner only — the shape used by other
     * modules (provenance, notes) that cite a block without naming a source.
     *
     * @return the block, or {@code null}; callers must not infer existence from it
     */
    public ContentBlock getMine(String ownerSubject, Long spaceId, Long blockId) {
        return contentBlockMapper.selectByIdSpaceOwner(blockId, spaceId, ownerSubject);
    }

    /**
     * Corrects one block of one source in place.
     *
     * <h3>Two guards, both added for F-26</h3>
     *
     * <ol>
     *   <li><b>Same-space cross-source write.</b> The lookup used to be
     *       {@code selectByIdSpaceOwner(blockId, spaceId, owner)}, which proves
     *       only that the block is in this space and owned by this caller — so
     *       {@code PUT .../sources/7/content-blocks/3} would happily edit block
     *       3 of source 8 in the same space. The read now carries the path
     *       {@code sourceId} and a mismatched pair collapses to 404, matching
     *       how every other ownership failure in this module is reported.</li>
     *   <li><b>No silent mutation of live content.</b>
     *       {@link PublishedRevisionRule#isLivePublishedContent} refuses an
     *       in-place edit of a row the source is currently serving while
     *       PUBLISHED, because that would rewrite text learners and AI answers
     *       already cite and that provenance rows point at. Rows of the
     *       draft / under-review revision stay editable, which is the whole
     *       purpose of the correction flow.</li>
     * </ol>
     *
     * @param ownerSubject authenticated caller
     * @param spaceId      path space id
     * @param sourceId     path source id; must be the block's own source
     * @param blockId      path block id
     * @param request      fields to overwrite; {@code null} fields stay as they are
     * @return the updated block
     * @throws ApiException 404 {@code SOURCE_NOT_FOUND} / {@code NOT_FOUND} when
     *         source or block is absent, not owned, or the block belongs to
     *         another source; 409 {@link #ERROR_PUBLISHED_REVISION_READONLY}
     *         when the row is live published content
     */
    @Transactional
    public ContentBlock update(String ownerSubject, Long spaceId, Long sourceId,
                               Long blockId, UpdateContentBlockRequest request) {
        Source source = sourceService.getMine(ownerSubject, spaceId, sourceId);
        if (source == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.SOURCE_NOT_FOUND,
                    "资料不存在。");
        }
        ContentBlock existing = contentBlockMapper
                .selectByIdAndSpaceSourceOwner(blockId, spaceId, sourceId, ownerSubject);
        if (existing == null || !sourceId.equals(existing.getSourceId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND,
                    "内容块不存在。");
        }
        if (PublishedRevisionRule.isLivePublishedContent(source, existing.getExtractionRevisionId())) {
            throw new ApiException(HttpStatus.CONFLICT, ERROR_PUBLISHED_REVISION_READONLY,
                    "该内容块属于已发布版本，请重新抽取后在新版本中修订。");
        }

        LocalDateTime now = LocalDateTime.now();
        if (request.normalizedText() != null) existing.setNormalizedText(request.normalizedText());
        if (request.structuredDataJson() != null) existing.setStructuredDataJson(request.structuredDataJson());
        if (request.locatorJson() != null) existing.setLocatorJson(request.locatorJson());
        existing.setUpdatedAt(now);
        contentBlockMapper.updateById(existing);
        return existing;
    }
}
