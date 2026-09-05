package com.aistudy.server.source.service;

import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.source.dto.CreateSourceRequest;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.mapper.SourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
 * uses — and only then touches source data:
 *
 * <pre>
 *   create:  parent = getMine(owner, spaceId)   // null → 404
 *            + insert source (spaceId, createdByUserId = owner)
 *
 *   list:    parent = getMine(owner, spaceId)   // null → 404
 *            + SELECT source WHERE space_id = ?
 *
 *   get:     SELECT s.* FROM source s
 *              JOIN learning_space ls ON ls.id = s.space_id
 *            WHERE s.id = ? AND s.space_id = ?
 *              AND ls.owner_subject = ?          // null → 404
 * </pre>
 *
 * <p>The single-source read is a pure JOIN query — the owner
 * condition lives in the SQL, so even a call with a correct owner's
 * spaceId but a sourceId belonging to ANOTHER space returns
 * {@code null} (no row matches all three predicates). This is the
 * anti-IDOR guarantee: absent vs not-owned are indistinguishable.
 *
 * <h3>Null/empty contract for the controller</h3>
 *
 * <ul>
 *   <li>{@link #create} returns {@code null} when the parent space
 *       is not found or not owned → controller maps to 404.</li>
 *   <li>{@link #listMine} returns {@code null} when the parent
 *       space is not found or not owned (404); returns an empty
 *       list when the space IS owned but has no sources (200).</li>
 *   <li>{@link #getMine} returns {@code null} when no row matches
 *       id + spaceId + owner (404).</li>
 * </ul>
 *
 * <h3>Transactions</h3>
 *
 * <p>{@link #create} is {@code @Transactional}: the parent-ownership
 * check and the insert commit atomically, so a future extension
 * that also writes storage metadata cannot leave half-applied rows.
 * Reads are single-statement and need no transaction.
 *
 * <h3>Unscoped BaseMapper methods</h3>
 *
 * <p>The inherited {@code selectById(id)} / {@code selectList(...)}
 * / {@code selectOne(...)} are NEVER called from this class — they
 * carry no space/owner predicate. See {@link SourceMapper} Javadoc.
 *
 * <h3>Out of scope for this slice</h3>
 *
 * <p>Upload, file parsing, IngestionJob, SourcePage, OCR, AI and
 * KnowledgePoint extraction are NOT implemented (BUSINESS-002
 * scope; docs/current-task.md).
 */
@Service
public class SourceService {

    /** Metadata registration status; no processing pipeline exists yet. */
    public static final String STATUS_REGISTERED = "REGISTERED";

    private final SourceMapper sourceMapper;
    private final LearningSpaceService learningSpaceService;

    public SourceService(SourceMapper sourceMapper,
                         LearningSpaceService learningSpaceService) {
        this.sourceMapper = sourceMapper;
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * Registers a Source inside the caller's own LearningSpace.
     *
     * <p>Fails (returns {@code null}) when the parent space does not
     * exist or belongs to another subject — both cases are
     * indistinguishable to the caller (404 semantics, anti-probing).
     *
     * @param ownerSubject authenticated JWT subject
     * @param spaceId      parent space id from the path
     * @param request      validated create request (title + sourceType)
     * @return the persisted source with generated id, or {@code null}
     *         when the parent space is absent / not owned
     */
    @Transactional
    public Source create(String ownerSubject, Long spaceId, CreateSourceRequest request) {
        // Parent ownership check — same owner-scoped query as
        // BUSINESS-001. Nothing is written if the space is not the
        // caller's own.
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        Source source = new Source();
        source.setSpaceId(spaceId);
        source.setTitle(request.title());
        source.setSourceType(request.sourceType());
        source.setStatus(STATUS_REGISTERED);
        source.setCreatedByUserId(ownerSubject);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);

        sourceMapper.insert(source);
        return source;
    }

    /**
     * Lists all sources of the caller's own LearningSpace, newest
     * first.
     *
     * @param ownerSubject authenticated JWT subject
     * @param spaceId      parent space id from the path
     * @return the sources (possibly empty), or {@code null} when the
     *         parent space is absent / not owned
     */
    public List<Source> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return sourceMapper.selectBySpaceId(spaceId);
    }

    /**
     * Returns ONE source of the caller's own LearningSpace.
     *
     * <p>The mapper query constrains id + spaceId + ownerSubject in
     * a single JOIN — {@code null} means any of: source absent,
     * source belongs to a different space, or parent space not
     * owned. 404 for all, by design.
     *
     * @param ownerSubject authenticated JWT subject
     * @param spaceId      parent space id from the path
     * @param sourceId     source id from the path
     * @return the owned source, or {@code null}
     */
    public Source getMine(String ownerSubject, Long spaceId, Long sourceId) {
        return sourceMapper.selectByIdAndSpaceAndOwner(sourceId, spaceId, ownerSubject);
    }
}
