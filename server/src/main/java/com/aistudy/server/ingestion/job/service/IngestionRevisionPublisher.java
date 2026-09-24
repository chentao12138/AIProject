package com.aistudy.server.ingestion.job.service;

import com.aistudy.server.ingestion.revision.mapper.ExtractionRevisionMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The one atomic step that makes a freshly extracted revision visible.
 *
 * <p>Stamps the revision onto the pages and blocks, moves the revision to
 * NEEDS_REVIEW, and only then points {@code source.current_extraction_revision_id}
 * at it. These four writes used to run as separate autocommit statements from
 * the worker thread, so a crash between them could leave the source's current
 * revision pointing at a DRAFT whose pages were never stamped.
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
        sourcePageMapper.stampRevisionOnSourcePages(spaceId, sourceId, revisionId, at);
        contentBlockMapper.stampRevisionOnSourceBlocks(spaceId, sourceId, revisionId, at);
        extractionRevisionMapper.updateStatus(revisionId, spaceId, sourceId, "NEEDS_REVIEW", null);
        sourceMapper.updateCurrentRevision(sourceId, spaceId, revisionId, at);
    }
}
