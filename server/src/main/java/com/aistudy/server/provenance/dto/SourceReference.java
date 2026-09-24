package com.aistudy.server.provenance.dto;

/**
 * Unified provenance jump target for KP / Question / Note citations.
 * One response is enough to navigate Source → Page → Block without N+1.
 */
public record SourceReference(
        Long sourceId,
        String sourceTitle,
        Long sourceAssetId,
        Long sourcePageId,
        Integer pageOrder,
        Integer sourcePageNumber,
        Integer printedPageNumber,
        Long contentBlockId,
        String blockType,
        String locator,
        Long extractionRevisionId,
        Integer revisionVersion
) {
}
