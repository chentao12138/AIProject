package com.aistudy.server.admin.bulk.controller;

import com.aistudy.server.admin.bulk.service.BulkOperationService;
import com.aistudy.server.ingestion.issue.entity.IngestionIssue;
import com.aistudy.server.ingestion.issue.mapper.IngestionIssueMapper;
import com.aistudy.server.ingestion.job.entity.IngestionJob;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.mapper.SourceMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/bulk")
@SecurityRequirement(name = "bearerAuth")
public class AdminBulkController {

    private final BulkOperationService bulkOperationService;
    private final KnowledgePointMapper knowledgePointMapper;
    private final QuestionMapper questionMapper;
    private final SourceMapper sourceMapper;
    private final IngestionIssueMapper ingestionIssueMapper;
    private final IngestionJobMapper ingestionJobMapper;

    public AdminBulkController(BulkOperationService bulkOperationService,
                               KnowledgePointMapper knowledgePointMapper,
                               QuestionMapper questionMapper,
                               SourceMapper sourceMapper,
                               IngestionIssueMapper ingestionIssueMapper,
                               IngestionJobMapper ingestionJobMapper) {
        this.bulkOperationService = bulkOperationService;
        this.knowledgePointMapper = knowledgePointMapper;
        this.questionMapper = questionMapper;
        this.sourceMapper = sourceMapper;
        this.ingestionIssueMapper = ingestionIssueMapper;
        this.ingestionJobMapper = ingestionJobMapper;
    }

    @PostMapping("/knowledge-points/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkPublishKnowledgePoints(@RequestBody List<Long> kpIds) {
        return bulkOperationService.bulkPublishKnowledgePoints(knowledgePointMapper, kpIds);
    }

    @PostMapping("/knowledge-points/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkArchiveKnowledgePoints(@RequestBody List<Long> kpIds) {
        return bulkOperationService.bulkArchiveKnowledgePoints(knowledgePointMapper, kpIds);
    }

    @PostMapping("/questions/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkPublishQuestions(@RequestBody List<Long> questionIds) {
        return bulkOperationService.bulkPublishQuestions(questionMapper, questionIds);
    }

    @PostMapping("/questions/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkArchiveQuestions(@RequestBody List<Long> questionIds) {
        return bulkOperationService.bulkArchiveQuestions(questionMapper, questionIds);
    }

    @PostMapping("/sources/review")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkReviewSources(@RequestBody List<Long> sourceIds) {
        return BulkOperationService.executeBulk(sourceIds, BulkOperationService.MAX_BATCH_SIZE, id -> {
            Source s = sourceMapper.selectById(id);
            if (s == null) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            return sourceMapper.updateReviewByIdAndSpace(id, s.getSpaceId(), "REVIEWED", now, null, null, now) > 0;
        });
    }

    @PostMapping("/sources/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkArchiveSources(@RequestBody List<Long> sourceIds) {
        return BulkOperationService.executeBulk(sourceIds, BulkOperationService.MAX_BATCH_SIZE, id -> {
            Source s = sourceMapper.selectById(id);
            if (s == null) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            return sourceMapper.archiveByIdAndSpace(id, s.getSpaceId(), "ARCHIVED", now, now) > 0;
        });
    }

    @PostMapping("/issues/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkResolveIssues(@RequestBody List<Long> issueIds) {
        return BulkOperationService.executeBulk(issueIds, BulkOperationService.MAX_BATCH_SIZE, id -> {
            IngestionIssue issue = ingestionIssueMapper.selectById(id);
            if (issue == null) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            return ingestionIssueMapper.resolveByIdAndSpace(id, issue.getSpaceId(), "RESOLVED", 0L, now, now) > 0;
        });
    }

    @PostMapping("/issues/ignore")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkIgnoreIssues(@RequestBody List<Long> issueIds) {
        return BulkOperationService.executeBulk(issueIds, BulkOperationService.MAX_BATCH_SIZE, id -> {
            IngestionIssue issue = ingestionIssueMapper.selectById(id);
            if (issue == null) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            return ingestionIssueMapper.ignoreByIdAndSpace(id, issue.getSpaceId(), "IGNORED", 0L, now, now) > 0;
        });
    }

    @PostMapping("/jobs/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public BulkOperationService.BulkResult bulkRetryJobs(@RequestBody List<Long> jobIds) {
        return BulkOperationService.executeBulk(jobIds, BulkOperationService.MAX_BATCH_SIZE, id -> {
            IngestionJob job = ingestionJobMapper.selectById(id);
            if (job == null || !"FAILED".equals(job.getStatus())) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            int updated = ingestionJobMapper.retryByIdAndSpace(
                    id, job.getSpaceId(), "PENDING", "QUEUED", now, (job.getRetryCount() == null ? 0 : job.getRetryCount()) + 1);
            return updated > 0;
        });
    }
}
