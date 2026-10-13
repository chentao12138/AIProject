package com.aistudy.server.ingestion.issue.service;

import com.aistudy.server.config.properties.IngestionReviewProperties;
import com.aistudy.server.source.outline.service.SourceOutlineNodeService;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.service.SourcePageService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the structuring-quality signals a reviewer needs (audit F-06).
 *
 * <p>Ordering and outline confidence live on rows owned by the {@code source}
 * module, so this component asks those owning services instead of importing their
 * mappers (see {@code ModuleBoundaryTest}). It is deliberately a separate bean
 * from {@code IngestionIssueService}: the issue service is reached from
 * {@code SourceExtractionGateway}, and letting it depend back on the source
 * module would close a bean cycle.
 */
@Component
public class StructuringReviewSignals {

    /**
     * @param lowConfidencePageIds   pages to raise one ORDER_LOW_CONFIDENCE issue
     *                               for, capped by the configured limit
     * @param omittedPageCount       pages past that cap, so the written issue can
     *                               say how many more exist instead of hiding them
     * @param lowConfidenceOutlineNodes nodes below the outline threshold
     */
    public record Signals(List<Long> lowConfidencePageIds, int omittedPageCount,
                          int lowConfidenceOutlineNodes) {

        public boolean hasNothing() {
            return lowConfidencePageIds.isEmpty() && lowConfidenceOutlineNodes == 0;
        }
    }

    private final SourcePageService sourcePageService;
    private final SourceOutlineNodeService sourceOutlineNodeService;
    private final IngestionReviewProperties reviewProperties;

    public StructuringReviewSignals(SourcePageService sourcePageService,
                                    SourceOutlineNodeService sourceOutlineNodeService,
                                    IngestionReviewProperties reviewProperties) {
        this.sourcePageService = sourcePageService;
        this.sourceOutlineNodeService = sourceOutlineNodeService;
        this.reviewProperties = reviewProperties;
    }

    public Signals scan(Long spaceId, Long revisionId) {
        List<SourcePage> lowPages = sourcePageService.listLowOrderConfidence(
                spaceId, revisionId, reviewProperties.getOrderConfidenceThreshold());
        int cap = Math.max(1, reviewProperties.getMaxIssuesPerRevision());
        List<Long> ids = new ArrayList<>();
        for (SourcePage page : lowPages) {
            if (ids.size() >= cap) {
                break;
            }
            ids.add(page.getId());
        }
        int outlineNodes = sourceOutlineNodeService.countLowConfidence(
                spaceId, revisionId, reviewProperties.getOutlineConfidenceThreshold());
        return new Signals(ids, Math.max(0, lowPages.size() - ids.size()), outlineNodes);
    }
}
