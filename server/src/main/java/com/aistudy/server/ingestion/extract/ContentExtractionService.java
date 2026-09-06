package com.aistudy.server.ingestion.extract;

import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.ingestion.extract.TxtMarkdownContentParser.ParsedBlock;
import com.aistudy.server.ingestion.extract.TxtMarkdownContentParser.ParsedDocument;
import com.aistudy.server.source.asset.entity.SourceAsset;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BUSINESS-006 — ContentExtractionService (architecture.md §6.1):
 * deterministic V1 text extraction for TXT / Markdown, wired into the
 * IngestionJob lifecycle by IngestionJobService.
 *
 * <p>This service performs NO AI calls. It loads the RAW bytes of a
 * SourceAsset through {@link StorageService} (bounded read, see
 * {@code aistudy.ingestion.text.max-document-bytes}), decodes with the
 * documented strict UTF-8 policy, parses deterministically
 * (TxtMarkdownContentParser) and persists ONE SourcePage plus its
 * ordered ContentBlocks with locator_json line ranges — the
 * provenance anchor for future KnowledgePoint citations.
 *
 * <h3>Failure semantics (honest)</h3>
 *
 * <ul>
 *   <li>CONTENT problems (invalid UTF-8, document too large) →
 *       {@link IngestionParseException} with a stable code + SAFE
 *       message; the job layer catches it and records a FAILED job.
 *       Nothing is persisted (parse is fully in-memory first).</li>
 *   <li>ENVIRONMENT problems (storage IO) → {@link IllegalStateException}
 *       propagates; the request fails, the job transaction rolls back
 *       (no partial content, no fake FAILED record).</li>
 * </ul>
 *
 * <p>Persistence happens ONLY after a complete successful parse, so a
 * FAILED job can never leave partial pages/blocks behind.
 */
@Service
public class ContentExtractionService {

    /** V1 text formats (extension, lowercase; allowlist mirrors
     * SourceAssetService upload allowlist for md/markdown/txt). */
    public static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown");

    public static final String PAGE_TYPE_BODY = "BODY";
    public static final String ORDER_STATUS_AUTO = "AUTO";

    private static final Logger log = LoggerFactory.getLogger(ContentExtractionService.class);

    private final StorageService storageService;
    private final SourcePageMapper sourcePageMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final long maxDocumentBytes;

    public ContentExtractionService(StorageService storageService,
                                    SourcePageMapper sourcePageMapper,
                                    ContentBlockMapper contentBlockMapper,
                                    @Value("${aistudy.ingestion.text.max-document-bytes:64MB}") DataSize maxDocumentBytes) {
        this.storageService = storageService;
        this.sourcePageMapper = sourcePageMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.maxDocumentBytes = maxDocumentBytes.toBytes();
    }

    /**
     * Loads + decodes + parses one text asset WITHOUT writing anything.
     *
     * @throws IngestionParseException for content problems
     * @throws IllegalStateException   for storage/environment failures
     */
    public ParsedDocument extractText(SourceAsset asset) {
        try (InputStream in = storageService.load(asset.getStorageKey())) {
            byte[] raw = readBounded(in, maxDocumentBytes);
            if (isMarkdown(asset)) {
                return TxtMarkdownContentParser.parseMarkdown(raw);
            }
            return TxtMarkdownContentParser.parseTxt(raw);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load asset for text ingestion", e);
        }
    }

    /**
     * Persists ONE SourcePage (pageOrder=1, BODY, AUTO) plus the
     * document's ContentBlocks in order. Called inside the ingestion
     * job transaction, AFTER a successful parse — all-or-nothing.
     *
     * @return the persisted page (blocks reference it)
     */
    @Transactional
    public SourcePage persistPageAndBlocks(SourceAsset asset, ParsedDocument doc) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        SourcePage page = new SourcePage();
        page.setSpaceId(asset.getSpaceId());
        page.setSourceId(asset.getSourceId());
        page.setSourceAssetId(asset.getId());
        page.setSourcePageNumber(null);
        page.setPageOrder(1);
        page.setPrintedPageNumber(null);
        page.setPageType(PAGE_TYPE_BODY);
        page.setOrderConfidence(null);
        page.setOrderStatus(ORDER_STATUS_AUTO);
        page.setExtractedText(doc.fullText());
        page.setExtractionConfidence(null);
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        sourcePageMapper.insert(page);

        List<ParsedBlock> blocks = doc.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            ParsedBlock block = blocks.get(i);
            ContentBlock cb = new ContentBlock();
            cb.setSpaceId(asset.getSpaceId());
            cb.setSourceId(asset.getSourceId());
            cb.setSourcePageId(page.getId());
            cb.setSourceOutlineNodeId(null);
            cb.setBlockType(block.type());
            cb.setSortOrder(i);
            cb.setNormalizedText(block.text());
            cb.setStructuredDataJson(null);
            cb.setLocatorJson("{\"lineStart\":" + block.lineStart()
                    + ",\"lineEnd\":" + block.lineEnd() + "}");
            cb.setCreatedAt(now);
            cb.setUpdatedAt(now);
            contentBlockMapper.insert(cb);
        }
        log.debug("persisted source_page {} with {} content blocks for asset {}",
                page.getId(), blocks.size(), asset.getId());
        return page;
    }

    // ==================== helpers ====================

    private static boolean isMarkdown(SourceAsset asset) {
        String name = asset.getOriginalName() == null ? "" : asset.getOriginalName().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    /** Bounded read: never loads more than {@code max} bytes. */
    private static byte[] readBounded(InputStream in, long max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new IngestionParseException(IngestionErrorCode.DOCUMENT_TOO_LARGE,
                        "document exceeds the configured text ingestion limit of "
                                + max + " bytes");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
