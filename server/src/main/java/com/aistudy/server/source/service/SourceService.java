package com.aistudy.server.source.service;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.source.content.service.SourceExtractionGateway;
import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.source.dto.CreateSourceRequest;
import com.aistudy.server.source.dto.UpdateSourceRequest;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.mapper.SourceMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BUSINESS-002 — application service for Source.
 *
 * <p>This is the ONLY caller of {@link SourceMapper}
 * (architecture.md: "Mapper 不被其他模块任意直接调用以绕过应用服务").
 *
 * <h3>Authorization model</h3>
 *
 * <p>A Source's ownership is derived from its parent LearningSpace
 * (V005 intentionally has no owner column). Every operation first
 * proves the parent space belongs to the caller via
 * {@link LearningSpaceService#getMine(String, Long)} — the SAME
 * owner-scoped query (id + ownerSubject) that BUSINESS-001 already
 * uses — and only then touches source data.
 *
 * <h3>Source lifecycle</h3>
 *
 * <p>Sources support the full lifecycle:
 * REGISTERED → PROCESSING → NEEDS_REVIEW → PUBLISHED → ARCHIVED
 * with REJECTED as a terminal alternative. ADMIN_MANUAL sources
 * bypass the ingestion pipeline and go straight to NEEDS_REVIEW or
 * PUBLISHED.
 *
 * <h3>Review is a state machine, not a string field (F-05)</h3>
 *
 * <p>{@link #review} used to persist whatever text the caller sent, so
 * {@code REGISTERED → PUBLISHED} (nothing extracted, nobody read it) and
 * invented statuses were both accepted. A review outcome now has to be one
 * of {@link #REVIEW_OUTCOMES} AND be a legal move from the source's current
 * status per {@link #REVIEW_TRANSITIONS}; anything else is refused with a
 * stable machine-readable code. See that map's Javadoc for why each edge
 * exists or is missing.
 */
@Service
public class SourceService {

    public static final String SOURCE_TYPE_DESKTOP_UPLOAD = "DESKTOP_UPLOAD";
    public static final String SOURCE_TYPE_DESKTOP_FOLDER_IMPORT = "DESKTOP_FOLDER_IMPORT";
    public static final String SOURCE_TYPE_ADMIN_UPLOAD = "ADMIN_UPLOAD";
    public static final String SOURCE_TYPE_ADMIN_MANUAL = "ADMIN_MANUAL";

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * Stable code: the requested review outcome is not in the review
     * vocabulary (400), as opposed to {@link #ERROR_ILLEGAL_REVIEW_TRANSITION}
     * which means "a real outcome, but not from this status" (409).
     */
    public static final String ERROR_INVALID_REVIEW_STATUS = "SOURCE_REVIEW_STATUS_INVALID";

    /** Stable code (409): a legal outcome reached from a status that forbids it. */
    public static final String ERROR_ILLEGAL_REVIEW_TRANSITION = "SOURCE_ILLEGAL_REVIEW_TRANSITION";

    /** Stable code (409): unresolved HIGH/OPEN ingestion issues block publishing. */
    public static final String ERROR_PUBLISH_BLOCKED_BY_ISSUES = "SOURCE_PUBLISH_BLOCKED_BY_ISSUES";

    /**
     * The closed set of review outcomes. The Source status vocabulary
     * (V032 {@code review_status} uses the same words) is wider than what a
     * reviewer may ASK for:
     *
     * <ul>
     *   <li>{@code NEEDS_REVIEW} — send back for human reading;</li>
     *   <li>{@code PUBLISHED} — approve, content becomes Search/AI visible;</li>
     *   <li>{@code REJECTED} — refuse, with a mandatory reason.</li>
     * </ul>
     *
     * <p>{@code REGISTERED} and {@code PROCESSING} are pipeline facts, not
     * decisions, and {@code ARCHIVED} is a lifecycle verb with its own
     * timestamped endpoint ({@link #archive}); accepting them here would let a
     * review call silently undo ingestion or skip the archive audit trail.
     */
    public static final Set<String> REVIEW_OUTCOMES = Set.of(
            STATUS_NEEDS_REVIEW, STATUS_PUBLISHED, STATUS_REJECTED);

    /**
     * Legal review outcomes per current source status. The WHY matters more
     * than the table, so each row is justified:
     *
     * <ul>
     *   <li>{@code REGISTERED → NEEDS_REVIEW, REJECTED}: a body typed by an
     *       admin (ADMIN_MANUAL) or a legacy row can be reviewed before any
     *       pipeline ran; {@code PUBLISHED} is deliberately absent — approving
     *       content that has never been read by a human is exactly the
     *       skip-level jump F-05 reported.</li>
     *   <li>{@code PROCESSING → NEEDS_REVIEW, REJECTED}: extraction finished
     *       (or stalled) without the worker advancing the source; approval
     *       still needs its own explicit review step.</li>
     *   <li>{@code NEEDS_REVIEW → PUBLISHED, REJECTED, NEEDS_REVIEW}: the
     *       decision point. Re-reviewing is allowed and cheap — it only
     *       refreshes the review audit columns.</li>
     *   <li>{@code PUBLISHED → PUBLISHED, REJECTED, NEEDS_REVIEW}:
     *       re-publishing is an IDEMPOTENT SUCCESS (see {@link #review});
     *       un-publishing a wrong document (REJECTED) or pulling it back for
     *       another read (NEEDS_REVIEW) both stop it being cited as live.</li>
     *   <li>{@code ARCHIVED → (none)}: archived content is out of the review
     *       chain; {@link #restore} is the only way back, and it returns to
     *       REGISTERED so the content has to be re-extracted and re-reviewed.</li>
     *   <li>{@code REJECTED → NEEDS_REVIEW}: the only way out of a rejection
     *       is a fresh review after the problem was fixed; approving straight
     *       from REJECTED would keep the rejection reason on a live document.</li>
     * </ul>
     */
    public static final Map<String, Set<String>> REVIEW_TRANSITIONS = reviewTransitions();

    private static Map<String, Set<String>> reviewTransitions() {
        Map<String, Set<String>> transitions = new LinkedHashMap<>();
        transitions.put(STATUS_REGISTERED, Set.of(STATUS_NEEDS_REVIEW, STATUS_REJECTED));
        transitions.put(STATUS_PROCESSING, Set.of(STATUS_NEEDS_REVIEW, STATUS_REJECTED));
        transitions.put(STATUS_NEEDS_REVIEW, Set.of(STATUS_PUBLISHED, STATUS_REJECTED, STATUS_NEEDS_REVIEW));
        transitions.put(STATUS_PUBLISHED, Set.of(STATUS_PUBLISHED, STATUS_REJECTED, STATUS_NEEDS_REVIEW));
        transitions.put(STATUS_ARCHIVED, Set.of());
        transitions.put(STATUS_REJECTED, Set.of(STATUS_NEEDS_REVIEW));
        return Map.copyOf(transitions);
    }

    private final SourceMapper sourceMapper;
    private final LearningSpaceService learningSpaceService;
    private final SourceExtractionGateway extractionGateway;

    public SourceService(SourceMapper sourceMapper,
                         LearningSpaceService learningSpaceService,
                         SourceExtractionGateway extractionGateway) {
        this.sourceMapper = sourceMapper;
        this.learningSpaceService = learningSpaceService;
        this.extractionGateway = extractionGateway;
    }


    @Transactional
    public Source create(String ownerSubject, Long spaceId, CreateSourceRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        Source source = new Source();
        source.setSpaceId(spaceId);
        source.setTitle(request.title());
        source.setSourceType(request.sourceType() != null ? request.sourceType() : SOURCE_TYPE_DESKTOP_UPLOAD);
        source.setStatus(STATUS_REGISTERED);
        source.setCreatedByUserId(ownerSubject);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);

        sourceMapper.insert(source);
        return source;
    }

    /**
     * ADMIN_MANUAL source with a REAL body (F-04 / D-01 / G-02).
     *
     * <p>Three writes, one transaction, identical in shape to what the file
     * pipeline produces:
     *
     * <ol>
     *   <li>the {@code source} row, status {@code NEEDS_REVIEW} (a typed body
     *       has been through no extraction pipeline, so it is reviewable the
     *       moment it exists — never {@code PUBLISHED} straight from the
     *       keyboard, which is what §3.14 forbids);</li>
     *   <li>a {@code source_page} (pageType BODY, order AUTO) plus ordered
     *       {@code content_block} rows derived by the shared TXT/Markdown
     *       extractor, so block types, UTF-8 byte bounding and
     *       {@code locator_json} line provenance match uploaded material;</li>
     *   <li>an {@code extraction_revision} for that content, stamped onto the
     *       new rows and pointed at by
     *       {@code source.current_extraction_revision_id}.</li>
     * </ol>
     *
     * <p>That third step is what makes the body visible to the existing
     * review → publish → page/block listing → Search/AI chain: those reads key
     * off the current revision pointer (NULL pointer = legacy), so without it
     * an admin-typed document is invisible no matter how many rows exist.
     *
     * <p>Ownership stays the owner-derived model of the rest of the module:
     * the admin must own the target space (there is no cross-owner space
     * lookup yet, and inventing one here would bypass the space governance
     * lane). Size/encoding of the body are enforced by the ingestion text
     * limits through {@link SourceExtractionGateway}, not by a second
     * hand-written ceiling.
     *
     * @param adminSubject authenticated ADMIN subject; also the audit creator
     * @param spaceId      target space, must be owned by {@code adminSubject}
     * @param title        non-blank material title (max 255, V005 column)
     * @param content      the body text; blank bodies are rejected rather than
     *                     silently producing an empty source (the old behavior)
     * @param markdown     {@code true} to parse Markdown, {@code false} for TXT
     * @return the persisted source with its current revision pointer filled in,
     *         or {@code null} when the space is not the caller's
     * @throws ApiException 400 for a blank body, 413 when the body exceeds the
     *                      text ingestion limit, 500 when no revision could be created
     */
    @Transactional
    public Source createManual(String adminSubject, Long spaceId, String title,
                               String content, boolean markdown) {
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                    "手工正文不能为空。");
        }
        if (learningSpaceService.getMine(adminSubject, spaceId) == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        Source source = new Source();
        source.setSpaceId(spaceId);
        source.setTitle(title);
        source.setSourceType(SOURCE_TYPE_ADMIN_MANUAL);
        source.setStatus(STATUS_NEEDS_REVIEW);
        source.setReviewStatus(STATUS_NEEDS_REVIEW);
        source.setCreatedByUserId(adminSubject);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);

        sourceMapper.insert(source);

        Long revisionId = extractionGateway.createManualBodyRevision(
                spaceId, source.getId(), title, content, markdown, now);
        // The gateway's publisher already moved the DB pointer; mirror it on the
        // returned entity so the create response is not a lie about its own content.
        source.setCurrentExtractionRevisionId(revisionId);
        return source;
    }

    /**
     * Markdown-by-default form of {@link #createManual(String, Long, String, String, boolean)}:
     * an admin typing a document almost always writes Markdown, and headings,
     * lists, tables and code have to become distinct block types for
     * provenance citation to be worth anything.
     */
    @Transactional
    public Source createManual(String adminSubject, Long spaceId, String title, String content) {
        return createManual(adminSubject, spaceId, title, content, true);
    }


    public List<Source> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return sourceMapper.selectBySpaceId(spaceId, ownerSubject);
    }

    public Source getMine(String ownerSubject, Long spaceId, Long sourceId) {
        return sourceMapper.selectByIdAndSpaceAndOwner(sourceId, spaceId, ownerSubject);
    }

    @Transactional
    public Source update(String ownerSubject, Long spaceId, Long sourceId, UpdateSourceRequest request) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null || STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.updateTitleByIdAndSpace(
                sourceId, spaceId,
                request.title() != null ? request.title() : existing.getTitle(),
                now);
        if (updated == 0) {
            return null;
        }
        existing.setTitle(request.title() != null ? request.title() : existing.getTitle());
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Source archive(String ownerSubject, Long spaceId, Long sourceId) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null || STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.archiveByIdAndSpace(sourceId, spaceId, STATUS_ARCHIVED, now, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_ARCHIVED);
        existing.setArchivedAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Source restore(String ownerSubject, Long spaceId, Long sourceId) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null || !STATUS_ARCHIVED.equals(existing.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.restoreByIdAndSpace(sourceId, spaceId, STATUS_REGISTERED, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_REGISTERED);
        existing.setArchivedAt(null);
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public Source transitionStatus(String ownerSubject, Long spaceId, Long sourceId, String newStatus) {
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.updateStatusByIdAndSpace(sourceId, spaceId, newStatus, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(newStatus);
        existing.setUpdatedAt(now);
        return existing;
    }

    /**
     * Applies a review decision (F-05 / D-06 / I-11).
     *
     * <p>Four gates, in this order, cheapest and most informative first:
     * vocabulary ({@link #REVIEW_OUTCOMES}), transition legality
     * ({@link #REVIEW_TRANSITIONS}), content quality (no unresolved HIGH/OPEN
     * ingestion blocker may be certified as reviewed), then the write. A blank
     * {@code rejectedReason} on {@code REJECTED} is refused, because a
     * rejection nobody can explain cannot be acted on by the owner.
     *
     * <h3>Both columns move together</h3>
     *
     * <p>{@code source.status} used to stay behind: only
     * {@code review_status} was written, so a "published" source kept
     * {@code status='NEEDS_REVIEW'} in the database and every visibility rule
     * keyed on {@code status='PUBLISHED'} (Search, AI, the read lane this
     * write side feeds) kept excluding it. {@code PUBLISHED} and
     * {@code REJECTED} now persist the business status too, in the same
     * transaction; {@code NEEDS_REVIEW} does not overwrite a source still
     * inside the pipeline, it only records the review verdict.
     *
     * <h3>Re-publishing is idempotent success</h3>
     *
     * <p>Deliberate choice over a conflict: the review screen re-sends
     * {@code PUBLISHED} after any content correction and after a double click,
     * and a 409 there would teach clients to ignore real conflicts. The row is
     * therefore written again (fresh {@code reviewedAt}/{@code reviewedBy}),
     * while the blocker gate is still applied, so a source that acquired a
     * HIGH/OPEN issue after going live cannot be silently re-certified.
     *
     * @param ownerSubject   caller whose parent-space ownership is re-proved
     * @param spaceId        path space id
     * @param sourceId       path source id
     * @param reviewStatus   requested outcome; must be in {@link #REVIEW_OUTCOMES}
     * @param reviewedBy     audit subject of the reviewer
     * @param rejectedReason required for {@code REJECTED}, ignored otherwise
     * @return the source with the applied decision, or {@code null} when the
     *         source is absent or not the caller's
     * @throws ApiException 400 {@link #ERROR_INVALID_REVIEW_STATUS},
     *         409 {@link #ERROR_ILLEGAL_REVIEW_TRANSITION},
     *         409 {@link #ERROR_PUBLISH_BLOCKED_BY_ISSUES}
     */
    @Transactional
    public Source review(String ownerSubject, Long spaceId, Long sourceId,
                         String reviewStatus, String reviewedBy, String rejectedReason) {
        if (!isReviewOutcome(reviewStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ERROR_INVALID_REVIEW_STATUS,
                    "审核结论只能是 NEEDS_REVIEW、PUBLISHED 或 REJECTED。");
        }
        Source existing = getMine(ownerSubject, spaceId, sourceId);
        if (existing == null) {
            return null;
        }
        if (!isLegalReviewTransition(existing.getStatus(), reviewStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, ERROR_ILLEGAL_REVIEW_TRANSITION,
                    "资料当前状态 " + existing.getStatus() + " 不能直接进入 " + reviewStatus + "。");
        }
        if (STATUS_REJECTED.equals(reviewStatus)
                && (rejectedReason == null || rejectedReason.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                    "驳回必须填写原因。");
        }
        if (STATUS_PUBLISHED.equals(reviewStatus)) {
            int blockers = extractionGateway.countUnresolvedBlockerIssues(spaceId, sourceId);
            if (blockers > 0) {
                throw new ApiException(HttpStatus.CONFLICT, ERROR_PUBLISH_BLOCKED_BY_ISSUES,
                        "存在 " + blockers + " 条未解决的高危摄取问题，无法发布。");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = sourceMapper.updateReviewByIdAndSpace(
                sourceId, spaceId, reviewStatus, now, reviewedBy, rejectedReason, now);
        if (updated == 0) {
            return null;
        }
        existing.setReviewStatus(reviewStatus);
        existing.setReviewedAt(now);
        existing.setReviewedBy(reviewedBy);
        existing.setRejectedReason(rejectedReason);
        existing.setUpdatedAt(now);

        // The business status is what every read-side visibility rule keys on,
        // so it has to be persisted, not just mirrored onto the returned entity.
        if (STATUS_PUBLISHED.equals(reviewStatus) || STATUS_REJECTED.equals(reviewStatus)) {
            String businessStatus = reviewStatus;
            sourceMapper.updateStatusByIdAndSpace(sourceId, spaceId, businessStatus, now);
            existing.setStatus(businessStatus);
        }
        return existing;
    }

    /** @return {@code true} when {@code reviewStatus} is an admissible review outcome */
    public static boolean isReviewOutcome(String reviewStatus) {
        return reviewStatus != null && REVIEW_OUTCOMES.contains(reviewStatus);
    }

    /**
     * The state machine's only question: may a source in {@code fromStatus}
     * receive {@code reviewStatus} as a review outcome?
     *
     * <p>Static and side-effect free so the matrix is unit-testable without a
     * database or a Spring context. Unknown {@code fromStatus} (a row written
     * before the vocabulary was pinned, or a value a lane invented) has no
     * entry in the map and therefore has no legal outcome — the safe
     * direction, and the reason re-reviewing legacy data needs an explicit
     * decision instead of a silent pass.
     */
    public static boolean isLegalReviewTransition(String fromStatus, String reviewStatus) {
        if (fromStatus == null || !isReviewOutcome(reviewStatus)) {
            return false;
        }
        Set<String> allowed = REVIEW_TRANSITIONS.getOrDefault(fromStatus, Set.of());
        return allowed.contains(reviewStatus);
    }
}

