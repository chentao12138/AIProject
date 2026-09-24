package com.aistudy.server.source.page.dto;

import java.util.List;

/**
 * Typed page-reorder request. Only allows fields the owner may change.
 * Page identity and source ownership are path-enforced server-side.
 */
public record ReorderPagesRequest(
        List<ReorderPageItem> pages
) {
    /**
     * One reorder row.
     *
     * @param pageId  existing SourcePage id (must belong to path source)
     * @param pageOrder new order (1-based, unique in the request)
     * @param pageType optional page type override
     * @param printedPageNumber optional printed page number override
     */
    public record ReorderPageItem(
            Long pageId,
            Integer pageOrder,
            String pageType,
            Integer printedPageNumber
    ) {
    }
}
