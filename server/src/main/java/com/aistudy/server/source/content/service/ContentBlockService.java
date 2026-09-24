package com.aistudy.server.source.content.service;

import com.aistudy.server.source.content.dto.UpdateContentBlockRequest;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.service.SourceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ContentBlockService {

    private final ContentBlockMapper contentBlockMapper;
    private final SourceService sourceService;

    public ContentBlockService(ContentBlockMapper contentBlockMapper,
                               SourceService sourceService) {
        this.contentBlockMapper = contentBlockMapper;
        this.sourceService = sourceService;
    }

    public List<ContentBlock> listMine(String ownerSubject, Long spaceId, Long sourceId, Long pageId) {
        if (sourceService.getMine(ownerSubject, spaceId, sourceId) == null) {
            return null;
        }
        if (pageId != null) {
            return contentBlockMapper.selectBySpaceSourcePageOwner(spaceId, sourceId, pageId, ownerSubject);
        }
        return contentBlockMapper.selectBySpaceSourceOwner(spaceId, sourceId, ownerSubject);
    }

    public ContentBlock getMine(String ownerSubject, Long spaceId, Long blockId) {
        return contentBlockMapper.selectByIdSpaceOwner(blockId, spaceId, ownerSubject);
    }

    @Transactional
    public ContentBlock update(String ownerSubject, Long spaceId, Long sourceId, Long blockId, UpdateContentBlockRequest request) {
        ContentBlock existing = contentBlockMapper.selectByIdSpaceOwner(blockId, spaceId, ownerSubject);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (request.normalizedText() != null) existing.setNormalizedText(request.normalizedText());
        if (request.structuredDataJson() != null) existing.setStructuredDataJson(request.structuredDataJson());
        if (request.locatorJson() != null) existing.setLocatorJson(request.locatorJson());
        existing.setUpdatedAt(now);
        contentBlockMapper.updateById(existing);
        return existing;
    }
}
