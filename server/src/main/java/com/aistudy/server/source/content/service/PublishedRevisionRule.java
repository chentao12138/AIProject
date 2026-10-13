package com.aistudy.server.source.content.service;

import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;

/**
 * The one rule that decides whether a page/block row is the content people are
 * currently being served, shared by every write path that edits extracted
 * content in place (F-26).
 *
 * <h3>Why in-place edits of live content are refused</h3>
 *
 * <p>Project-wide visibility rule: content is readable by Search/AI only when
 * {@code source.status = 'PUBLISHED'} and the row belongs to
 * {@code source.current_extraction_revision_id} (a NULL pointer is the
 * documented legacy shape, whose NULL-revision rows are what reads see).
 * Rewriting {@code normalized_text} on such a row changes what learners and
 * AI answers are already citing, with no new revision, no published_at and no
 * review — and provenance (KnowledgePointSource cites a block id) would
 * silently point at different words. Draft and under-review revisions are
 * exactly the rows that may still be corrected; live content is corrected by
 * re-extracting into a new revision.
 */
public final class PublishedRevisionRule {

    private PublishedRevisionRule() {
    }

    /**
     * @param source        the source owning the row; {@code null} counts as
     *                      not live, so a caller that cannot load the parent
     *                      gets its own 404 rather than a confusing 409
     * @param rowRevisionId the page/block row's {@code extraction_revision_id},
     *                      possibly {@code null} for pre-V053 legacy rows
     * @return {@code true} when the row is the content a published source is
     *         currently serving, so an in-place edit must be refused
     */
    public static boolean isLivePublishedContent(Source source, Long rowRevisionId) {
        if (source == null || !SourceService.STATUS_PUBLISHED.equals(source.getStatus())) {
            return false;
        }
        Long current = source.getCurrentExtractionRevisionId();
        // Legacy pointer (NULL): the source's NULL-revision rows are what reads serve.
        return current == null ? rowRevisionId == null : current.equals(rowRevisionId);
    }
}
