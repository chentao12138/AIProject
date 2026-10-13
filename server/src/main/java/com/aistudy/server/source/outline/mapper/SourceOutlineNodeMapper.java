package com.aistudy.server.source.outline.mapper;

import com.aistudy.server.source.outline.entity.SourceOutlineNode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SourceOutlineNodeMapper extends BaseMapper<SourceOutlineNode> {

    @Select("SELECT * FROM source_outline_node WHERE id = #{id} AND space_id = #{spaceId}")
    SourceOutlineNode selectByIdAndSpace(@Param("id") Long id, @Param("spaceId") Long spaceId);

    @Select("SELECT * FROM source_outline_node WHERE space_id = #{spaceId} AND source_id = #{sourceId} ORDER BY sort_order ASC, id ASC")
    List<SourceOutlineNode> selectBySpaceAndSource(@Param("spaceId") Long spaceId, @Param("sourceId") Long sourceId);

    /**
     * Outline nodes produced by ONE extraction revision, in document order.
     * The revision diff needs both trees at once, and a revision always owns its
     * own rows, so this is the only safe way to compare two versions.
     */
    @Select("SELECT * FROM source_outline_node "
            + "WHERE extraction_revision_id = #{revisionId} AND space_id = #{spaceId} "
            + "ORDER BY sort_order ASC, id ASC")
    List<SourceOutlineNode> selectByRevision(@Param("revisionId") Long revisionId,
                                             @Param("spaceId") Long spaceId);

    /** Direct children count used to refuse delete-with-children (409). */
    @Select("SELECT COUNT(*) FROM source_outline_node WHERE parent_id = #{nodeId}")
    int countChildren(@Param("nodeId") Long nodeId);
}
