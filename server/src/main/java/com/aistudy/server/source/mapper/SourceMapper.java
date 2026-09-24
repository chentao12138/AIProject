package com.aistudy.server.source.mapper;

import com.aistudy.server.source.entity.Source;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * BUSINESS-002 — production MyBatis-Plus mapper for {@link Source}.
 *
 * <p>Extends {@link BaseMapper} for insert; all READS are explicit
 * owner-scoped {@code @Select} queries that JOIN through
 * {@code learning_space} so the owner subject is enforced in SQL.
 *
 * <h3>Why JOIN-based reads (IDOR protection)</h3>
 *
 * A Source's ownership is derived from its parent LearningSpace
 * (V005 has no owner_subject column by design). Every read therefore
 * joins {@code learning_space} and constrains
 * {@code ls.owner_subject = ?}:
 *
 * <ul>
 *   <li>{@link #selectByIdAndSpaceAndOwner} — used by
 *       {@code GET /api/v1/spaces/{spaceId}/sources/{sourceId}}:
 *       {@code WHERE s.id = ? AND s.space_id = ? AND
 *       ls.owner_subject = ?}. This single query resists BOTH
 *       "user2 guesses user1's spaceId/sourceId" AND "correct owner
 *       but sourceId actually belongs to another space" — all three
 *       mismatches collapse to "no row".</li>
 *   <li>{@link #selectBySpaceId} — used by the list endpoint AFTER
 *       the controller/service has already proven the parent space
 *       is owned by the caller (owner-scoped parent check). The
 *       list query itself is space-scoped; the owner boundary was
 *       established by the parent check in the same request.</li>
 * </ul>
 *
 * <p>Note: {@link BaseMapper} still exposes unscoped methods
 * ({@code selectById(id)}, {@code selectList(...)},
 * {@code selectOne(...)}) — they are inherited and MUST NOT be
 * called by the service layer, because they carry no space/owner
 * predicate. Only {@link com.aistudy.server.source.service.SourceService}
 * calls this mapper, and it never uses the unscoped inherited
 * methods.
 */
@Mapper
public interface SourceMapper extends BaseMapper<Source> {

    /**
     * Returns the source identified by {@code id}, but ONLY IF it
     * belongs to {@code spaceId} AND that space is owned by
     * {@code ownerSubject}. Any mismatch — wrong id, wrong space,
     * wrong owner — returns {@code null}, so callers cannot
     * distinguish "source does not exist" from "source exists but
     * you may not see it" (anti-probing).
     *
     * @param sourceId     the source id from the path
     * @param spaceId      the parent space id from the path
     * @param ownerSubject the authenticated JWT subject
     * @return the owned source, or {@code null}
     */
    @Select("SELECT s.* "
            + "FROM source s "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE s.id = #{sourceId} "
            + "  AND s.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    Source selectByIdAndSpaceAndOwner(@Param("sourceId") Long sourceId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);

    /**
     * Space-scoped source lookup for INTERNAL workers that already
     * proved ownership via a parent entity (e.g. IngestionJob created
     * under a verified owner). Never used by request-path controllers.
     */
    @Select("SELECT s.* FROM source s WHERE s.id = #{sourceId} AND s.space_id = #{spaceId}")
    Source selectByIdAndSpace(@Param("sourceId") Long sourceId,
                              @Param("spaceId") Long spaceId);

    /**
     * Returns all sources of one space, newest first. The caller
     * MUST have already proven (owner-scoped) that {@code spaceId}
     * belongs to the current user; {@code ownerSubject} re-states that
     * boundary in SQL so this query can never be reached without it.
     *
     * @param spaceId the parent space id from the path
     * @param ownerSubject the authenticated JWT subject owning the space
     * @return sources of the space, newest first
     */
    @Select("SELECT s.* FROM source s JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE s.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY s.created_at DESC, s.id DESC")
    List<Source> selectBySpaceId(@Param("spaceId") Long spaceId,
                                 @Param("ownerSubject") String ownerSubject);

    @Update("UPDATE source SET title = #{title}, updated_at = #{updatedAt} "
            + "WHERE id = #{sourceId} AND space_id = #{spaceId}")
    int updateTitleByIdAndSpace(@Param("sourceId") Long sourceId,
                                @Param("spaceId") Long spaceId,
                                @Param("title") String title,
                                @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE source SET status = #{status}, archived_at = #{archivedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{sourceId} AND space_id = #{spaceId}")
    int archiveByIdAndSpace(@Param("sourceId") Long sourceId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("archivedAt") LocalDateTime archivedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE source SET status = #{status}, archived_at = NULL, updated_at = #{updatedAt} "
            + "WHERE id = #{sourceId} AND space_id = #{spaceId}")
    int restoreByIdAndSpace(@Param("sourceId") Long sourceId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE source SET status = #{status}, updated_at = #{updatedAt} "
            + "WHERE id = #{sourceId} AND space_id = #{spaceId}")
    int updateStatusByIdAndSpace(@Param("sourceId") Long sourceId,
                                 @Param("spaceId") Long spaceId,
                                 @Param("status") String status,
                                 @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE source SET review_status = #{reviewStatus}, reviewed_at = #{reviewedAt}, "
            + "reviewed_by = #{reviewedBy}, rejected_reason = #{rejectedReason}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{sourceId} AND space_id = #{spaceId}")
    int updateReviewByIdAndSpace(@Param("sourceId") Long sourceId,
                                 @Param("spaceId") Long spaceId,
                                 @Param("reviewStatus") String reviewStatus,
                                 @Param("reviewedAt") LocalDateTime reviewedAt,
                                 @Param("reviewedBy") String reviewedBy,
                                 @Param("rejectedReason") String rejectedReason,
                                 @Param("updatedAt") LocalDateTime updatedAt);

    /** Atomic current-revision pointer switch after successful extraction. */
    @Update("UPDATE source SET current_extraction_revision_id = #{revisionId}, updated_at = #{updatedAt} "
            + "WHERE id = #{sourceId} AND space_id = #{spaceId}")
    int updateCurrentRevision(@Param("sourceId") Long sourceId,
                              @Param("spaceId") Long spaceId,
                              @Param("revisionId") Long revisionId,
                              @Param("updatedAt") LocalDateTime updatedAt);

    // ==================== ADMIN governance reads ====================
    // Governance deliberately crosses owners (api-guidelines.md §3), so these
    // carry no owner predicate but are still bounded by space_id where the
    // caller supplies one. They exist here so ADMIN screens reach persistence
    // through this module instead of ad-hoc SQL elsewhere.

    @Select("<script>"
            + "SELECT s.id, s.space_id, s.title, s.source_type, s.status, "
            + "  s.current_extraction_revision_id, s.created_at, ls.owner_subject "
            + "FROM source s JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE (#{spaceId} IS NULL OR s.space_id = #{spaceId}) "
            + "  AND (#{like} IS NULL OR s.title LIKE #{like}) "
            + "ORDER BY s.created_at DESC, s.id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<Map<String, Object>> selectAdminList(@Param("like") String like,
                                              @Param("spaceId") Long spaceId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    @Select("SELECT s.*, ls.owner_subject FROM source s "
            + "JOIN learning_space ls ON ls.id = s.space_id WHERE s.id = #{sourceId}")
    Map<String, Object> selectAdminDetail(@Param("sourceId") Long sourceId);

    @Select("SELECT current_extraction_revision_id FROM source WHERE id = #{sourceId} AND space_id = #{spaceId}")
    Long selectCurrentRevisionByIdAndSpace(@Param("sourceId") Long sourceId,
                                           @Param("spaceId") Long spaceId);
}
