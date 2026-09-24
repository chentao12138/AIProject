package com.aistudy.server.ingestion.revision.service;

import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.aistudy.server.ingestion.revision.mapper.ExtractionRevisionMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.mapper.SourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExtractionRevisionService {

    private final ExtractionRevisionMapper extractionRevisionMapper;
    private final SourceMapper sourceMapper;
    private final LearningSpaceService learningSpaceService;

    public ExtractionRevisionService(ExtractionRevisionMapper extractionRevisionMapper,
                                     SourceMapper sourceMapper,
                                     LearningSpaceService learningSpaceService) {
        this.extractionRevisionMapper = extractionRevisionMapper;
        this.sourceMapper = sourceMapper;
        this.learningSpaceService = learningSpaceService;
    }

    public List<ExtractionRevision> listMine(String ownerSubject, Long spaceId, Long sourceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return extractionRevisionMapper.selectBySpaceAndSource(spaceId, sourceId);
    }

    @Transactional
    public ExtractionRevision create(String ownerSubject, Long spaceId, Long sourceId, ExtractionRevision request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        Source source = sourceMapper.selectByIdAndSpaceAndOwner(sourceId, spaceId, ownerSubject);
        if (source == null) {
            return null;
        }
        return insertRevision(spaceId, sourceId, request);
    }

    /**
     * Internal worker path: job already proved source+space ownership.
     * Never pass a null owner to {@link #create}; workers use this method
     * after loading the trusted requester from the ingestion job/source.
     */
    @Transactional
    public ExtractionRevision createForVerifiedSource(Long spaceId, Long sourceId, ExtractionRevision request) {
        if (spaceId == null || sourceId == null) {
            return null;
        }
        Source source = sourceMapper.selectByIdAndSpace(sourceId, spaceId);
        if (source == null) {
            return null;
        }
        return insertRevision(spaceId, sourceId, request);
    }

    private ExtractionRevision insertRevision(Long spaceId, Long sourceId, ExtractionRevision request) {
        Integer maxVersion = extractionRevisionMapper.selectMaxVersionBySource(sourceId);
        int nextVersion = maxVersion == null ? 1 : maxVersion + 1;
        LocalDateTime now = LocalDateTime.now();
        ExtractionRevision revision = new ExtractionRevision();
        revision.setSpaceId(spaceId);
        revision.setSourceId(sourceId);
        revision.setVersion(nextVersion);
        revision.setStatus("DRAFT");
        revision.setExtractorVersion(request.getExtractorVersion());
        revision.setOcrEngine(request.getOcrEngine());
        revision.setOcrModel(request.getOcrModel());
        revision.setCreatedAt(now);
        extractionRevisionMapper.insert(revision);
        return revision;
    }
}
