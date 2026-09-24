package com.aistudy.server.ingestion.revision.mapper;

import com.aistudy.server.ingestion.revision.entity.ExtractionRevision;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExtractionRevisionMapper extends BaseMapper<ExtractionRevision> {

    @Select("SELECT * FROM extraction_revision WHERE id = #{id} AND space_id = #{spaceId}")
    ExtractionRevision selectByIdAndSpace(@Param("id") Long id, @Param("spaceId") Long spaceId);

    @Select("SELECT * FROM extraction_revision WHERE space_id = #{spaceId} AND source_id = #{sourceId} ORDER BY version DESC")
    List<ExtractionRevision> selectBySpaceAndSource(@Param("spaceId") Long spaceId, @Param("sourceId") Long sourceId);

    @Select("SELECT MAX(version) FROM extraction_revision WHERE source_id = #{sourceId}")
    Integer selectMaxVersionBySource(@Param("sourceId") Long sourceId);

    /** Revisions of a source, scoped also by space for safety. */
    @Select("SELECT * FROM extraction_revision WHERE id = #{id} AND space_id = #{spaceId} AND source_id = #{sourceId}")
    ExtractionRevision selectByIdSpaceSource(@Param("id") Long id,
                                             @Param("spaceId") Long spaceId,
                                             @Param("sourceId") Long sourceId);

    @org.apache.ibatis.annotations.Update(
            "UPDATE extraction_revision SET status = #{status}, published_at = #{publishedAt} "
                    + "WHERE id = #{id} AND space_id = #{spaceId} AND source_id = #{sourceId}")
    int updateStatus(@Param("id") Long id,
                     @Param("spaceId") Long spaceId,
                     @Param("sourceId") Long sourceId,
                     @Param("status") String status,
                     @Param("publishedAt") java.time.LocalDateTime publishedAt);
}
