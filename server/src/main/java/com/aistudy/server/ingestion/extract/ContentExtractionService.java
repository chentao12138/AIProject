package com.aistudy.server.ingestion.extract;

import com.aistudy.server.source.page.entity.SourcePage;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ContentExtractionService {

    String PAGE_TYPE_BODY = "BODY";
    String PAGE_TYPE_IMAGE = "IMAGE";
    String ORDER_STATUS_AUTO = "AUTO";

    List<SourcePage> extract(MultipartFile file, Long spaceId, Long sourceId, Long sourceAssetId);

    record ParsedBlock(String type, String text, int lineStart, int lineEnd) {
    }

    record ParseResult(List<ParsedBlock> blocks) {
    }

    static ParseResult parsePageText(String text) {
        int lines = text.isEmpty() ? 0 : (int) text.chars().filter(ch -> ch == '\n').count() + 1;
        return new ParseResult(List.of(new ParsedBlock("TEXT", text, 1, lines)));
    }
}
