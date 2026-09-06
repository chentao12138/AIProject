package com.aistudy.server.ingestion.job.mapper;

import com.aistudy.server.ingestion.job.entity.IngestionJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-005 — production MyBatis-Plus mapper for {@link IngestionJob}.
 *
 * <p>Extends {@link BaseMapper} for insert only. All READS are
 * explicit owner-scoped {@code @Select} queries that JOIN through
 * {@code source} AND {@code learning_space} so the owner subject and
 * the source↔space consistency are enforced in SQL (BUSINESS-004
 * pattern):
 *
 * <ul>
 *   <li>{@link #selectByIdSpaceOwner} — detail read for the
 *       space-scoped job endpoint: {@code WHERE ij.id = ? AND
 *       ij.space_id = ? AND ls.owner_subject = ?}. The JOIN also
 *       requires {@code source.space_id == ingestion_job.space_id},
 *       so a job whose source lives in ANOTHER space cannot be
 *       reached even with a correct owner + space path.</li>
 *   <li>{@link #selectBySpaceSourceOwner} — job history list for one
 *       owned source, newest first, same owner + same-space
 *       predicates.</li>
 * </ul>
 *
 * <p>Inherited unscoped BaseMapper reads are NEVER used by the
 * service layer.
 */
@Mapper
public interface IngestionJobMapper extends BaseMapper<IngestionJob> {

    /**
     * Returns the job iff ALL of: it exists, belongs to
     * {@code spaceId}, its source really belongs to that space
     * (JOIN), and the space is owned by {@code ownerSubject}. Any
     * mismatch returns {@code null} (404, anti-probing).
     */
    @Select("SELECT ij.* "
            + "FROM ingestion_job ij "
            + "JOIN source s "
            + "  ON s.id = ij.source_id "
            + " AND s.space_id = ij.space_id "
            + "JOIN learning_space ls ON ls.id = ij.space_id "
            + "WHERE ij.id = #{jobId} "
            + "  AND ij.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    IngestionJob selectByIdSpaceOwner(@Param("jobId") Long jobId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);

    /**
     * Lists jobs of one owned source, newest first. The owner
     * predicate and the source↔space consistency are enforced IN SQL
     * via the JOINs — this method can never list jobs of a source
     * the caller does not own.
     */
    @Select("SELECT ij.* "
            + "FROM ingestion_job ij "
            + "JOIN source s "
            + "  ON s.id = ij.source_id "
            + " AND s.space_id = ij.space_id "
            + "JOIN learning_space ls ON ls.id = ij.space_id "
            + "WHERE ij.space_id = #{spaceId} "
            + "  AND ij.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY ij.created_at DESC, ij.id DESC")
    List<IngestionJob> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                                @Param("sourceId") Long sourceId,
                                                @Param("ownerSubject") String ownerSubject);

    /**
     * Retry reset (FAILED → PENDING): explicit NULL assignments are
     * required because {@code updateById} skips null fields and would
     * leave stale error/timing values behind.
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ingestion_job "
                    + "SET status = 'PENDING', stage = 'QUEUED', progress_percent = 0, "
                    + "    started_at = NULL, finished_at = NULL, "
                    + "    retry_count = #{retryCount}, "
                    + "    error_code = NULL, error_message = NULL, "
                    + "    updated_at = #{updatedAt} "
                    + "WHERE id = #{id}")
    int resetForRetry(@Param("id") Long id,
                      @Param("retryCount") int retryCount,
                      @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * Duplicate guard (BUSINESS-006): counts non-terminal or already
     * succeeded jobs for one (space, source, asset). Scope is the
     * asset itself — no owner predicate needed because the caller
     * already proved ownership via {@code getMine} before this query.
     */
    @Select("SELECT COUNT(*) FROM ingestion_job "
            + "WHERE space_id = #{spaceId} "
            + "  AND source_id = #{sourceId} "
            + "  AND asset_id = #{assetId} "
            + "  AND status IN ('PENDING', 'RUNNING', 'SUCCEEDED')")
    int countActiveOrSucceeded(@Param("spaceId") Long spaceId,
                               @Param("sourceId") Long sourceId,
                               @Param("assetId") Long assetId);
}
