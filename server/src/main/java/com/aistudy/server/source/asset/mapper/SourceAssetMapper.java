package com.aistudy.server.source.asset.mapper;

import com.aistudy.server.source.asset.entity.SourceAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-004 — production MyBatis-Plus mapper for {@link SourceAsset}.
 *
 * <p>Extends {@link BaseMapper} for insert only. All READS are
 * explicit owner-scoped {@code @Select} queries that JOIN through
 * {@code source} AND {@code learning_space} so the owner subject and
 * the source↔space consistency are enforced in SQL:
 *
 * <ul>
 *   <li>{@link #selectByIdSpaceSourceOwner} — detail read:
 *       {@code WHERE sa.id = ? AND sa.space_id = ? AND sa.source_id = ?
 *       AND ls.owner_subject = ?}. The JOIN also requires
 *       {@code source.space_id == source_asset.space_id}, so an asset
 *       whose source lives in ANOTHER space cannot be reached even
 *       with correct owner + space path — one row or null (404).</li>
 *   <li>{@link #selectBySpaceSourceOwner} — list read with the same
 *       owner + same-space predicates, newest first. The owner
 *       predicate stays in SQL even though the service validates the
 *       parent source first (defense in depth).</li>
 * </ul>
 *
 * <p>Inherited unscoped BaseMapper reads ({@code selectById},
 * {@code selectList}, {@code selectOne}) are NEVER used by the
 * service layer — they carry no space/source/owner predicate.
 */
@Mapper
public interface SourceAssetMapper extends BaseMapper<SourceAsset> {

    /**
     * Returns the asset iff ALL of: it exists, belongs to
     * {@code spaceId}, belongs to {@code sourceId}, that source
     * really belongs to that space (JOIN), and the space is owned by
     * {@code ownerSubject}. Any mismatch returns {@code null} (404,
     * anti-probing — absent vs not-owned vs cross-space are
     * indistinguishable).
     */
    @Select("SELECT sa.* "
            + "FROM source_asset sa "
            + "JOIN source s "
            + "  ON s.id = sa.source_id "
            + " AND s.space_id = sa.space_id "
            + "JOIN learning_space ls ON ls.id = sa.space_id "
            + "WHERE sa.id = #{assetId} "
            + "  AND sa.space_id = #{spaceId} "
            + "  AND sa.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    SourceAsset selectByIdSpaceSourceOwner(@Param("assetId") Long assetId,
                                           @Param("spaceId") Long spaceId,
                                           @Param("sourceId") Long sourceId,
                                           @Param("ownerSubject") String ownerSubject);

    /**
     * Lists assets of one owned source, newest first. The owner
     * predicate and the source↔space consistency are enforced IN SQL
     * via the JOINs — this method can never list assets of a source
     * the caller does not own, even if the service layer were bypassed.
     */
    @Select("SELECT sa.* "
            + "FROM source_asset sa "
            + "JOIN source s "
            + "  ON s.id = sa.source_id "
            + " AND s.space_id = sa.space_id "
            + "JOIN learning_space ls ON ls.id = sa.space_id "
            + "WHERE sa.space_id = #{spaceId} "
            + "  AND sa.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sa.created_at DESC, sa.id DESC")
    List<SourceAsset> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                               @Param("sourceId") Long sourceId,
                                               @Param("ownerSubject") String ownerSubject);
}
