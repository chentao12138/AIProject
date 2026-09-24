package com.aistudy.server.ingestion.extract;

import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocxContentParser implements ContentExtractionService {

    private final ContentBlockMapper contentBlockMapper;
    private final SourcePageMapper sourcePageMapper;

    public DocxContentParser(ContentBlockMapper contentBlockMapper,
                             SourcePageMapper sourcePageMapper) {
        this.contentBlockMapper = contentBlockMapper;
        this.sourcePageMapper = sourcePageMapper;
    }

    @Override
    public List<SourcePage> extract(MultipartFile file, Long spaceId, Long sourceId, Long sourceAssetId) {
        List<SourcePage> pages = new ArrayList<>();
        try (InputStream in = file.getInputStream(); XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder text = new StringBuilder();
            List<String> paragraphs = new ArrayList<>();
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String pText = paragraph.getText();
                text.append(pText).append('\n');
                paragraphs.add(pText);
            }

            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            // Re-running an ingestion job must replace this asset's unpublishd
            // rows instead of appending a second copy of the document.
            contentBlockMapper.deleteBySpaceSourceAsset(spaceId, sourceId, sourceAssetId);
            sourcePageMapper.deleteBySpaceSourceAsset(spaceId, sourceId, sourceAssetId);

            SourcePage page = new SourcePage();
            page.setSpaceId(spaceId);
            page.setSourceId(sourceId);
            page.setSourceAssetId(sourceAssetId);
            page.setSourcePageNumber(1);
            page.setPageOrder(1);
            page.setPrintedPageNumber(null);
            page.setPageType("BODY");
            page.setOrderConfidence(1.0);
            page.setOrderStatus("AUTO");
            page.setExtractedText(text.toString());
            page.setExtractionConfidence(1.0);
            page.setCreatedAt(now);
            page.setUpdatedAt(now);
            sourcePageMapper.insert(page);

            for (int j = 0; j < paragraphs.size(); j++) {
                ContentBlock cb = new ContentBlock();
                cb.setSpaceId(spaceId);
                cb.setSourceId(sourceId);
                cb.setSourcePageId(page.getId());
                cb.setBlockType("PARAGRAPH");
                cb.setSortOrder(j);
                cb.setNormalizedText(paragraphs.get(j));
                cb.setStructuredDataJson(null);
                cb.setLocatorJson("{\"page\":1,\"lineStart\":" + (j + 1) + ",\"lineEnd\":" + (j + 1) + "}");
                cb.setCreatedAt(now);
                cb.setUpdatedAt(now);
                contentBlockMapper.insert(cb);
            }

            pages.add(page);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return pages;
    }
}
