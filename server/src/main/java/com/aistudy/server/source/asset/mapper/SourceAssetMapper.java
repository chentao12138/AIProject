package com.aistudy.server.source.asset.mapper;

import com.aistudy.server.source.asset.entity.SourceAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SourceAssetMapper extends BaseMapper<SourceAsset> {

    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.id = #{assetId} AND sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    SourceAsset selectByIdSpaceSourceOwner(@Param("assetId") Long assetId,
                                           @Param("spaceId") Long spaceId,
                                           @Param("sourceId") Long sourceId,
                                           @Param("ownerSubject") String ownerSubject);

    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sa.created_at DESC, sa.id DESC")
    List<SourceAsset> selectBySpaceSourceOwner(@Param("spaceId") Long spaceId,
                                                @Param("sourceId") Long sourceId,
                                                @Param("ownerSubject") String ownerSubject);

    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} AND sa.sha256 = #{sha256} AND ls.owner_subject = #{ownerSubject}")
    SourceAsset selectBySpaceSourceAndSha256(@Param("spaceId") Long spaceId,
                                             @Param("sourceId") Long sourceId,
                                             @Param("sha256") String sha256,
                                             @Param("ownerSubject") String ownerSubject);

    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.space_id = #{spaceId} AND sa.sha256 = #{sha256} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sa.created_at ASC, sa.id ASC LIMIT 1")
    SourceAsset selectBySpaceAndSha256(@Param("spaceId") Long spaceId,
                                       @Param("sha256") String sha256,
                                       @Param("ownerSubject") String ownerSubject);

    /**
     * F-13 precheck support — owner-scoped first asset that displays
     * under {@code originalName} in the space, used to tell
     * SAME_NAME_DIFFERENT_HASH (content change) apart from a real
     * duplicate. Name is display metadata: it was never unique and
     * stays non-unique.
     */
    @Select("SELECT sa.* FROM source_asset sa "
            + "JOIN source s ON s.id = sa.source_id "
            + "JOIN learning_space ls ON ls.id = s.space_id "
            + "WHERE sa.space_id = #{spaceId} AND sa.original_name = #{originalName} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY sa.created_at ASC, sa.id ASC LIMIT 1")
    SourceAsset selectFirstBySpaceAndName(@Param("spaceId") Long spaceId,
                                          @Param("originalName") String originalName,
                                          @Param("ownerSubject") String ownerSubject);

    @Select("SELECT storage_key FROM source_asset WHERE storage_key IN (#{storageKeys})")
    List<String> selectExistingStorageKeys(@Param("storageKeys") List<String> storageKeys);

    @Select("SELECT sa.* FROM source_asset sa "
            + "WHERE sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} "
            + "ORDER BY sa.created_at ASC, sa.id ASC")
    List<SourceAsset> selectBySpaceSource(@Param("spaceId") Long spaceId,
                                          @Param("sourceId") Long sourceId);

    /**
     * One asset pinned to both its source and space (ADMIN raw-bytes read;
     * no owner predicate because governance crosses owners by design).
     */
    @Select("SELECT sa.* FROM source_asset sa "
            + "WHERE sa.id = #{assetId} AND sa.source_id = #{sourceId} AND sa.space_id = #{spaceId}")
    SourceAsset selectByIdSpaceSource(@Param("assetId") Long assetId,
                                      @Param("sourceId") Long sourceId,
                                      @Param("spaceId") Long spaceId);

    /**
     * F-12 idempotency probe — the exact tuple that must never be
     * duplicated by a ZIP re-extraction: same space, source, entry
     * path AND identical bytes. Returns the existing row so the
     * extractor reuses it (no insert, no new RAW object) instead of
     * creating the silent duplicate V008's non-unique indexes allowed.
     */
    @Select("SELECT sa.* FROM source_asset sa "
            + "WHERE sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} "
            + "  AND sa.original_relative_path = #{relativePath} AND sa.sha256 = #{sha256} "
            + "ORDER BY sa.asset_version ASC, sa.id ASC LIMIT 1")
    SourceAsset selectBySpaceSourcePathAndSha(@Param("spaceId") Long spaceId,
                                              @Param("sourceId") Long sourceId,
                                              @Param("relativePath") String relativePath,
                                              @Param("sha256") String sha256);

    /**
     * F-12 versioning probe — every row already seen for one
     * (space, source, entry path), oldest version first. The extractor
     * derives the next {@code asset_version} from the last element.
     */
    @Select("SELECT sa.* FROM source_asset sa "
            + "WHERE sa.space_id = #{spaceId} AND sa.source_id = #{sourceId} "
            + "  AND sa.original_relative_path = #{relativePath} "
            + "ORDER BY sa.asset_version ASC, sa.id ASC")
    java.util.List<SourceAsset> selectBySpaceSourceAndPath(@Param("spaceId") Long spaceId,
                                                           @Param("sourceId") Long sourceId,
                                                           @Param("relativePath") String relativePath);

    /**
     * F-12 supersede-and-retain — stamp the previous head row of a
     * path when a new byte version takes over. The row is NEVER
     * deleted; its id remains valid for pages/blocks/entries already
     * bound to it.
     */
    @Update("UPDATE source_asset SET superseded_at = #{supersededAt} "
            + "WHERE id = #{assetId} AND superseded_at IS NULL")
    int markSuperseded(@Param("assetId") Long assetId,
                       @Param("supersededAt") java.time.LocalDateTime supersededAt);

    /**
     * F-13 serialization point — take the parent space's row lock for
     * the rest of the caller's transaction. Concurrent duplicate
     * uploads of identical bytes in one space are serialized on this
     * lock so the post-lock {@link #selectForUpdateBySpaceAndSha256}
     * read sees the other transaction's committed insert; without it
     * both transactions would check → miss → insert.
     */
    @Select("SELECT id FROM learning_space WHERE id = #{spaceId} FOR UPDATE")
    Long lockSpaceForUpload(@Param("spaceId") Long spaceId);

    /**
     * F-13 — locking read of the duplicate probe. A FOR UPDATE read
     * always observes the latest committed row (not the REPEATABLE READ
     * snapshot), which is what makes the skip/importAgain decision
     * deterministic after {@link #lockSpaceForUpload} is held.
     * Single table on purpose: joining source/learning_space here would
     * widen the lock set; ownership is proven by the space lock + the
     * getMine check the service already ran.
     */
    @Select("SELECT * FROM source_asset "
            + "WHERE space_id = #{spaceId} AND sha256 = #{sha256} "
            + "ORDER BY created_at ASC, id ASC LIMIT 1 FOR UPDATE")
    SourceAsset selectForUpdateBySpaceAndSha256(@Param("spaceId") Long spaceId,
                                                @Param("sha256") String sha256);
}
