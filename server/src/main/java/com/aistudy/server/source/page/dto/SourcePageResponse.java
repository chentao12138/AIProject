package com.aistudy.server.source.page.dto;

import com.aistudy.server.source.page.entity.SourcePage;

import java.time.LocalDateTime;

/**
 * BUSINESS-006 — typed SourcePage response.
 *
 * <p>Exposes the extracted text (the page payload the UI renders).
 * Confidence fields are included for contract stability and stay
 * {@code null} until confidence-producing extraction exists.
 *
 * @param id                     page id
 * @param spaceId                parent space id
 * @param sourceId               parent source id
 * @param sourceAssetId          source asset this page was extracted from
 * @param sourcePageNumber       PDF-internal page number (null for text assets)
 * @param pageOrder              final reading order
 * @param printedPageNumber      recognized printed page number (null in V1)
 * @param pageType               COVER | TOC | BODY | APPENDIX | OTHER
 * @param orderConfidence        ordering confidence (null in V1)
 * @param orderStatus            AUTO | NEEDS_REVIEW | CONFIRMED
 * @param extractedText          full decoded/normalized page text
 * @param extractionConfidence   extraction confidence (null in V1)
 * @param createdAt              creation timestamp
 * @param updatedAt              last-update timestamp
 */
public record SourcePageResponse(
        Long id,
        Long spaceId,
        Long sourceId,
        Long sourceAssetId,
        Integer sourcePageNumber,
        Integer pageOrder,
        Integer printedPageNumber,
        String pageType,
        Double orderConfidence,
        String orderStatus,
        String extractedText,
        Double extractionConfidence,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static SourcePageResponse from(SourcePage page) {
        return new SourcePageResponse(
                page.getId(),
                page.getSpaceId(),
                page.getSourceId(),
                page.getSourceAssetId(),
                page.getSourcePageNumber(),
                page.getPageOrder(),
                page.getPrintedPageNumber(),
                page.getPageType(),
                page.getOrderConfidence(),
                page.getOrderStatus(),
                page.getExtractedText(),
                page.getExtractionConfidence(),
                page.getCreatedAt(),
                page.getUpdatedAt());
    }
}
