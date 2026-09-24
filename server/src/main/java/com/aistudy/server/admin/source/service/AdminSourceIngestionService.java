package com.aistudy.server.admin.source.service;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.ingestion.issue.entity.IngestionIssue;
import com.aistudy.server.ingestion.issue.mapper.IngestionIssueMapper;
import com.aistudy.server.ingestion.job.entity.IngestionJob;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.ingestion.job.service.IngestionJobService;
import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.aistudy.server.ingestion.revision.mapper.ExtractionRevisionMapper;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADMIN source / ingestion governance reads and writes
 * (api-guidelines.md §3, ADR-043).
 *
 * <p>Governance is the one place that legitimately crosses owners, so these
 * queries carry a space predicate instead of an owner predicate and are reached
 * only behind {@code ROLE_ADMIN}.
 *
 * <p>The response maps keep the column names the governance screens were
 * built against; SQL itself lives in each owning module's mapper, never here.
 */
@Service
public class AdminSourceIngestionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SourceMapper sourceMapper;
    private final IngestionJobMapper ingestionJobMapper;
    private final IngestionJobService ingestionJobService;
    private final ExtractionRevisionMapper extractionRevisionMapper;
    private final IngestionIssueMapper ingestionIssueMapper;
    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final SourceAssetMapper sourceAssetMapper;

    public AdminSourceIngestionService(SourceMapper sourceMapper,
                                       IngestionJobMapper ingestionJobMapper,
                                       IngestionJobService ingestionJobService,
                                       ExtractionRevisionMapper extractionRevisionMapper,
                                       IngestionIssueMapper ingestionIssueMapper,
                                       SourcePageMapper sourcePageMapper,
                                       ContentBlockMapper contentBlockMapper,
                                       SourceAssetMapper sourceAssetMapper) {
        this.sourceMapper = sourceMapper;
        this.ingestionJobMapper = ingestionJobMapper;
        this.ingestionJobService = ingestionJobService;
        this.extractionRevisionMapper = extractionRevisionMapper;
        this.ingestionIssueMapper = ingestionIssueMapper;
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.sourceAssetMapper = sourceAssetMapper;
    }

    public List<Map<String, Object>> listSources(String query, Long spaceId, int page, int size) {
        String like = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        int safePage = Math.max(0, page);
        return sourceMapper.selectAdminList(like, spaceId, safeSize, safePage * safeSize);
    }

    public Map<String, Object> sourceDetail(Long sourceId) {
        Map<String, Object> row = sourceMapper.selectAdminDetail(sourceId);
        if (row == null || row.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.SOURCE_NOT_FOUND,
                    "资料不存在。");
        }
        return row;
    }

    public List<ExtractionRevision> revisions(Long spaceId, Long sourceId) {
        return extractionRevisionMapper.selectBySpaceAndSource(spaceId, sourceId);
    }

    public List<Map<String, Object>> jobs(Long sourceId, Long spaceId) {
        return ingestionJobMapper.selectAdminBySpaceAndSource(sourceId, spaceId);
    }

    /**
     * Requeue and actually re-dispatch a job. Returns the job's current
     * reported state, or throws 404 when the row does not exist.
     */
    public Map<String, Object> retryJob(Long jobId) {
        IngestionJob job = ingestionJobService.adminRetry(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND, "摄取任务不存在。");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", job.getId());
        result.put("status", job.getStatus());
        result.put("stage", job.getStage());
        result.put("retry_count", job.getRetryCount());
        return result;
    }

    public List<IngestionIssue> issues(Long sourceId, Long spaceId) {
        return ingestionIssueMapper.selectBySpaceAndSource(spaceId, sourceId);
    }

    /** Pages of the source's current extraction revision. */
    public List<SourcePage> pages(Long sourceId, Long spaceId) {
        Long revisionId = currentRevisionId(sourceId, spaceId);
        return revisionId == null
                ? List.of()
                : sourcePageMapper.selectByRevision(revisionId, spaceId);
    }

    @Transactional
    public Map<String, Object> reorderPage(Long pageId, Long spaceId, Integer pageOrder) {
        int updated = sourcePageMapper.updateOrderAndConfirmByIdAndSpace(
                pageId, spaceId, pageOrder, LocalDateTime.now());
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND, "页面不存在。");
        }
        SourcePage page = sourcePageMapper.selectByIdAndSpace(pageId, spaceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", page.getId());
        result.put("page_order", page.getPageOrder());
        result.put("order_status", page.getOrderStatus());
        return result;
    }

    /**
     * Governance correction of extracted content. Both fields are optional and
     * applied in a single statement, so a request cannot leave a half-applied
     * edit behind.
     */
    @Transactional
    public Map<String, Object> editBlock(Long blockId, Long spaceId, String normalizedText,
                                         String blockType) {
        if (contentBlockMapper.selectByIdAndSpace(blockId, spaceId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND, "内容块不存在。");
        }
        contentBlockMapper.updateTextAndTypeByIdAndSpace(
                blockId, spaceId, normalizedText, blockType, LocalDateTime.now());
        ContentBlock block = contentBlockMapper.selectByIdAndSpace(blockId, spaceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", block.getId());
        result.put("space_id", block.getSpaceId());
        result.put("source_id", block.getSourceId());
        result.put("block_type", block.getBlockType());
        result.put("normalized_text", block.getNormalizedText());
        return result;
    }

    /** Locates the RAW bytes for streaming; the caller keeps the HTTP concern. */
    public RawAsset rawAsset(Long sourceId, Long assetId, Long spaceId) {
        SourceAsset asset = sourceAssetMapper.selectByIdSpaceSource(assetId, sourceId, spaceId);
        if (asset == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOT_FOUND, "原始文件不存在。");
        }
        return new RawAsset(asset.getStorageKey(), asset.getMimeType(), asset.getOriginalName());
    }

    private Long currentRevisionId(Long sourceId, Long spaceId) {
        return sourceMapper.selectCurrentRevisionByIdAndSpace(sourceId, spaceId);
    }

    /** A stored RAW asset resolved for download. */
    public record RawAsset(String storageKey, String mimeType, String originalName) {
    }
}
