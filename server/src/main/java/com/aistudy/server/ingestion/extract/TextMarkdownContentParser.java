package com.aistudy.server.ingestion.extract;

import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class TextMarkdownContentParser implements ContentExtractionService {

    private final ContentBlockMapper contentBlockMapper;
    private final SourcePageMapper sourcePageMapper;
    private final long maxDocumentBytes;

    public TextMarkdownContentParser(ContentBlockMapper contentBlockMapper,
                                     SourcePageMapper sourcePageMapper,
                                     @Value("${aistudy.ingestion.text.max-document-bytes:64MB}")
                                     DataSize maxDocumentBytes) {
        this.contentBlockMapper = contentBlockMapper;
        this.sourcePageMapper = sourcePageMapper;
        this.maxDocumentBytes = maxDocumentBytes.toBytes();
    }

    @Override
    public List<SourcePage> extract(MultipartFile file, Long spaceId, Long sourceId, Long sourceAssetId) {
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read uploaded text asset", e);
        }
        if (raw.length > maxDocumentBytes) {
            throw new IngestionParseException(IngestionErrorCode.DOCUMENT_TOO_LARGE,
                    "document is " + raw.length + " bytes; the ingestion limit is "
                            + maxDocumentBytes + " bytes");
        }

        // Hand the stored bytes to the parser untouched: decoding them here first
        // replaced malformed sequences with U+FFFD, so the parser's strict UTF-8
        // check never saw the real input and ENCODING_ERROR could not fire.
        boolean markdown = isMarkdown(file.getOriginalFilename(), file.getContentType());
        TxtMarkdownContentParser.ParsedDocument parsed = markdown
                ? TxtMarkdownContentParser.parseMarkdown(raw)
                : TxtMarkdownContentParser.parseTxt(raw);

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        // Re-running a job replaces this asset's unpublished rows rather than
        // appending a second copy of the document.
        contentBlockMapper.deleteBySpaceSourceAsset(spaceId, sourceId, sourceAssetId);
        sourcePageMapper.deleteBySpaceSourceAsset(spaceId, sourceId, sourceAssetId);

        SourcePage page = new SourcePage();
        page.setSpaceId(spaceId);
        page.setSourceId(sourceId);
        page.setSourceAssetId(sourceAssetId);
        page.setSourcePageNumber(null);   // PDF-internal page number; null for text (SourcePageResponse)
        page.setPageOrder(1);
        page.setPrintedPageNumber(null);
        page.setPageType("BODY");
        page.setOrderConfidence(1.0);
        page.setOrderStatus("AUTO");
        page.setExtractedText(parsed.fullText());
        page.setExtractionConfidence(1.0);
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        sourcePageMapper.insert(page);

        for (int j = 0; j < parsed.blocks().size(); j++) {
            TxtMarkdownContentParser.ParsedBlock block = parsed.blocks().get(j);
            ContentBlock cb = new ContentBlock();
            cb.setSpaceId(spaceId);
            cb.setSourceId(sourceId);
            cb.setSourcePageId(page.getId());
            cb.setBlockType(block.type());
            cb.setSortOrder(j);
            cb.setNormalizedText(block.text());
            cb.setStructuredDataJson(null);
            cb.setLocatorJson("{\"page\":1,\"lineStart\":" + block.lineStart() + ",\"lineEnd\":" + block.lineEnd() + "}");
            cb.setCreatedAt(now);
            cb.setUpdatedAt(now);
            contentBlockMapper.insert(cb);
        }

        List<SourcePage> pages = new ArrayList<>();
        pages.add(page);
        return pages;
    }

    private static boolean isMarkdown(String originalName, String contentType) {
        if (originalName != null) {
            String lower = originalName.toLowerCase();
            if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                return true;
            }
        }
        return contentType != null && contentType.startsWith("text/markdown");
    }
}
