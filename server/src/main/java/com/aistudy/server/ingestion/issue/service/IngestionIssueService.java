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
     * Append-only addition for the Source publish gate, reached through
     * {@code SourceExtractionGateway} because the source module may not import
     * this one (see {@code ModuleBoundaryTest}).
     *
     * @return number of unresolved blocker rows (severity HIGH, status OPEN)
     *         raised against one source inside one space; {@code 0} means the
     *         source may be published
     */
    public int countUnresolvedBlockers(Long spaceId, Long sourceId) {
        return ingestionIssueMapper.countOpenBlockersBySource(spaceId, sourceId);
    }

    /**
     * Pipeline producer for the ordering and outline issues (audit F-06).
     *
     * <p>Only OCR confidence and partial extraction were ever produced
     * automatically, so the requirement's "low ordering confidence / low outline
     * confidence goes to review" had no path: a source could be published with a
     * scrambled page order nobody was ever told about. The scan lives in
     * {@link StructuringReviewSignals}, which reads the owning services and caps
     * the page-level rows; this method only writes what it is handed.
     *
     * <p>Severity is {@code MEDIUM} on purpose: these need a reviewer decision
     * (resolve or ignore), but they do not block publication the way a
     * {@code HIGH} {@code EXTRACTION_PARTIAL_FAILURE} does.
     *
     * @return rows written
     */
    @Transactional
    public int recordOrderingIssues(Long spaceId, Long sourceId, Long jobId, Long revisionId,
                                    java.util.List<Long> lowConfidencePageIds, int omittedPageCount,
                                    int lowConfidenceOutlineNodes) {
        LocalDateTime now = LocalDateTime.now();
        int written = 0;
        for (Long pageId : lowConfidencePageIds) {
            String suffix = omittedPageCount > 0
                    ? " (plus " + omittedPageCount + " more pages below the same threshold)"
                    : "";
            insert(spaceId, sourceId, jobId, revisionId, pageId, "ORDER_LOW_CONFIDENCE", "MEDIUM",
                    "Page order confidence below threshold for "
                            + "aistudy.ingestion.review.order-confidence-threshold" + suffix,
                    "Page ordering needs review" + suffix, now);
            written++;
        }
        if (lowConfidenceOutlineNodes > 0) {
            insert(spaceId, sourceId, jobId, revisionId, null, "OUTLINE_LOW_CONFIDENCE", "MEDIUM",
                    lowConfidenceOutlineNodes + " outline node(s) below the confidence threshold",
                    "Extracted outline needs review", now);
            written++;
        }
        return written;
    }

    private void insert(Long spaceId, Long sourceId, Long jobId, Long revisionId, Long pageId,
                        String issueType, String severity, String message, String safeMessage,
                        LocalDateTime now) {
        IngestionIssue issue = new IngestionIssue();
        issue.setSpaceId(spaceId);
        issue.setSourceId(sourceId);
        issue.setIngestionJobId(jobId);
        issue.setExtractionRevisionId(revisionId);
        issue.setSourcePageId(pageId);
        issue.setIssueType(issueType);
        issue.setSeverity(severity);
        issue.setStatus("OPEN");
        issue.setMessage(message);
        issue.setSafeMessage(safeMessage);
        issue.setCreatedAt(now);
        ingestionIssueMapper.insert(issue);
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
