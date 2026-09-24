package com.aistudy.server.ai.source;

import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AiSourceDiagnosisService {

    private final SourcePageMapper sourcePageMapper;

    public AiSourceDiagnosisService(SourcePageMapper sourcePageMapper) {
        this.sourcePageMapper = sourcePageMapper;
    }

    public String extractSourceText(String ownerSubject, Long spaceId, Long sourceId) {
        List<SourcePage> pages = sourcePageMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
        if (pages == null || pages.isEmpty()) {
            return null;
        }
        return pages.stream()
                .map(SourcePage::getExtractedText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));
    }
}
