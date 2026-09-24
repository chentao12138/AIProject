package com.aistudy.server.ingestion.issue.service;

import com.aistudy.server.ingestion.issue.entity.IngestionIssue;
import com.aistudy.server.ingestion.issue.mapper.IngestionIssueMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class IngestionIssueService {

    private final IngestionIssueMapper ingestionIssueMapper;
    private final LearningSpaceService learningSpaceService;

    public IngestionIssueService(IngestionIssueMapper ingestionIssueMapper,
                                LearningSpaceService learningSpaceService) {
        this.ingestionIssueMapper = ingestionIssueMapper;
        this.learningSpaceService = learningSpaceService;
    }

    public List<IngestionIssue> listByJob(String ownerSubject, Long spaceId, Long jobId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return ingestionIssueMapper.selectBySpaceAndJob(spaceId, jobId, ownerSubject);
    }

    @Transactional
    public IngestionIssue resolve(String ownerSubject, Long spaceId, Long issueId, Long resolvedBy) {
        IngestionIssue issue = ingestionIssueMapper.selectByIdAndSpace(issueId, spaceId, ownerSubject);
        if (issue == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        issue.setStatus("RESOLVED");
        issue.setResolvedBy(resolvedBy);
        issue.setResolvedAt(now);
        ingestionIssueMapper.updateById(issue);
        return issue;
    }

    @Transactional
    public IngestionIssue ignore(String ownerSubject, Long spaceId, Long issueId, Long resolvedBy) {
        IngestionIssue issue = ingestionIssueMapper.selectByIdAndSpace(issueId, spaceId, ownerSubject);
        if (issue == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        issue.setStatus("IGNORED");
        issue.setResolvedBy(resolvedBy);
        issue.setResolvedAt(now);
        ingestionIssueMapper.updateById(issue);
        return issue;
    }

    /**
     * Pipeline producer for automatic issues (C-5.10).
     * Low OCR confidence → OCR_LOW_CONFIDENCE; partial extraction → EXTRACTION_PARTIAL_FAILURE.
     */
    @Transactional
    public void recordIfLowConfidence(Long spaceId, Long sourceId, Long jobId,
                                      Long revisionId, boolean lowOcr, boolean partialFailure) {
        LocalDateTime now = LocalDateTime.now();
        if (lowOcr) {
            IngestionIssue issue = new IngestionIssue();
            issue.setSpaceId(spaceId);
            issue.setSourceId(sourceId);
            issue.setIngestionJobId(jobId);
            issue.setExtractionRevisionId(revisionId);
            issue.setIssueType("OCR_LOW_CONFIDENCE");
            issue.setSeverity("MEDIUM");
            issue.setStatus("OPEN");
            issue.setSafeMessage("OCR extraction confidence below threshold; review extracted text.");
            issue.setMessage("OCR extraction confidence below threshold");
            issue.setCreatedAt(now);
            ingestionIssueMapper.insert(issue);
        }
        if (partialFailure) {
            IngestionIssue issue = new IngestionIssue();
            issue.setSpaceId(spaceId);
            issue.setSourceId(sourceId);
            issue.setIngestionJobId(jobId);
            issue.setExtractionRevisionId(revisionId);
            issue.setIssueType("EXTRACTION_PARTIAL_FAILURE");
            issue.setSeverity("HIGH");
            issue.setStatus("OPEN");
            issue.setSafeMessage("One or more assets failed extraction; partial results require review.");
            issue.setMessage("Partial extraction failure");
            issue.setCreatedAt(now);
            ingestionIssueMapper.insert(issue);
        }
    }
}
