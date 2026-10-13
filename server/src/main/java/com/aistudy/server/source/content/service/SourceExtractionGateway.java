package com.aistudy.server.source.content.service;

import java.time.LocalDateTime;

/**
 * The extraction-side writes the Source write path needs and cannot own.
 *
 * <p>Extracted content lives in the ingestion module
 * ({@code extraction_revision}, the TXT/Markdown extractor, the revision
 * publisher) and ingestion unresolved-issue rows live there too. The
 * {@code source} module must not import {@code ingestion}: ingestion already
 * depends on source, so a source-to-ingestion import would close a module
 * cycle that {@code ModuleBoundaryTest} fails the build on
 * (architecture.md §4). This interface is the inversion point — the Source
 * module states what it needs, and the ADMIN governance lane
 * ({@code admin.source.service}, which already depends on both modules)
 * provides the implementation. Wiring is by Spring, never by import.
 *
 * <h3>Why these three operations and no others</h3>
 *
 * <p>They are exactly the extraction facts that make a Source's WRITE side
 * honest, and each one is invisible to a reader of {@code source} alone:
 *
 * <ol>
 *   <li>a typed admin body has to become a real revision with real
 *       pages/blocks, or {@code ADMIN_MANUAL} sources stay empty while
 *       looking reviewable (F-04);</li>
 *   <li>publishing has to consult unresolved ingestion blockers, or a
 *       partially failed extraction gets certified as reviewed (§3.14:
 *       "blocker issue 禁止 publish").</li>
 * </ol>
 *
 * <p>Deliberately absent: moving {@code extraction_revision.status} to
 * {@code PUBLISHED} when the source publishes. That write needs the ingestion
 * revision mapper, and the cross-module-mapper-import ceiling
 * ({@code ModuleBoundaryTest}) is exactly at its measured limit, so the
 * revision keeps the {@code NEEDS_REVIEW} status the extractor published it
 * with. Nothing in the visibility rule reads revision status — content is
 * live when {@code source.status = 'PUBLISHED'} and the row belongs to
 * {@code source.current_extraction_revision_id} — and {@code NEEDS_REVIEW}
 * rows are already protected from re-ingestion cleanup, which only deletes
 * {@code DRAFT}/{@code REJECTED} revisions.
 */
public interface SourceExtractionGateway {

    /** {@code extraction_revision.extractor_version} for a Markdown manual body. */
    String EXTRACTOR_MANUAL_MARKDOWN = "MANUAL-MARKDOWN-1";

    /** {@code extraction_revision.extractor_version} for a plain-text manual body. */
    String EXTRACTOR_MANUAL_TEXT = "MANUAL-TEXT-1";

    /**
     * Parses an admin-typed body exactly like an uploaded TXT/Markdown asset
     * and persists it as one BODY {@code source_page} plus ordered
     * {@code content_block} rows under a NEW extraction revision, then makes
     * that revision the source's current one (pages/blocks stamped, revision
     * moved to {@code NEEDS_REVIEW}, {@code source.current_extraction_revision_id}
     * pointed at it) — all of it through the same
     * {@code IngestionRevisionPublisher} the file pipeline uses, so a manual
     * source needs no manual database edits to reach review, page/block
     * listing, Search or AI.
     *
     * <p>A manual body has no {@code source_asset}: the rows are written with
     * a null asset id, which is the documented shape of a page without a
     * backing file (V010 {@code source_asset_id NULL}).
     *
     * <p>Size and encoding are validated by the shared text-ingestion limits
     * ({@code aistudy.ingestion.text.max-document-bytes}), not by a second
     * hand-written ceiling here.
     *
     * @param spaceId  space of the source; ownership already proved by the caller
     * @param sourceId persisted source the body belongs to
     * @param title    source title, used only to name the pseudo file
     * @param content  raw body text; must be non-blank
     * @param markdown {@code true} to parse as Markdown, {@code false} for TXT
     * @param at       timestamp stamped onto revision/pages/blocks
     * @return the id of the new current extraction revision, never {@code null}
     * @throws com.aistudy.server.common.problem.ApiException 413 when the body
     *         exceeds the text ingestion limit, 400 when it cannot be decoded,
     *         500 when no revision could be created
     */
    Long createManualBodyRevision(Long spaceId, Long sourceId, String title,
                                  String content, boolean markdown, LocalDateTime at);

    /**
     * Count of unresolved blocker issues for one source:
     * {@code ingestion_issue} rows with {@code severity = 'HIGH'} and
     * {@code status = 'OPEN'} (the values the pipeline itself writes,
     * V042 default {@code OPEN} plus {@code IngestionIssueService}
     * {@code HIGH} for partial extraction failure).
     *
     * <p>Only these two columns' literal values count; {@code MEDIUM}
     * low-confidence rows and already {@code RESOLVED}/{@code IGNORED}
     * rows never block a publish.
     *
     * @return 0 when nothing blocks publishing
     */
    int countUnresolvedBlockerIssues(Long spaceId, Long sourceId);
}
