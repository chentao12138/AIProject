package com.aistudy.server.ingestion.extract;

import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class TextMarkdownContentParser implements ContentExtractionService {

    private final ContentBlockMapper contentBlockMapper;
    private final SourcePageMapper sourcePageMapper;

    public TextMarkdownContentParser(ContentBlockMapper contentBlockMapper,
                                     SourcePageMapper sourcePageMapper) {
        this.contentBlockMapper = contentBlockMapper;
        this.sourcePageMapper = sourcePageMapper;
    }

    @Override
    public List<SourcePage> extract(MultipartFile file, Long spaceId, Long sourceId, Long sourceAssetId) {
        String text;
        try {
            String contentType = file.getContentType();
            if (contentType != null && contentType.startsWith("text/markdown")) {
                text = new String(file.getBytes(), StandardCharsets.UTF_8);
            } else {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    text = sb.toString();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        boolean markdown = isMarkdown(file.getOriginalFilename(), file.getContentType());
        TxtMarkdownContentParser.ParsedDocument parsed = markdown
                ? TxtMarkdownContentParser.parseMarkdown(text.getBytes(StandardCharsets.UTF_8))
                : TxtMarkdownContentParser.parseTxt(text.getBytes(StandardCharsets.UTF_8));

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
