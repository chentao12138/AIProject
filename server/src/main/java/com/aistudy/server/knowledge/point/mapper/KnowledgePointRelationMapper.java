package com.aistudy.server.knowledge.point.mapper;

import com.aistudy.server.knowledge.point.entity.KnowledgePointRelation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgePointRelationMapper extends BaseMapper<KnowledgePointRelation> {

    @Select("SELECT kpr.* FROM knowledge_point_relation kpr "
            + "JOIN learning_space ls ON ls.id = kpr.space_id "
            + "WHERE kpr.source_knowledge_point_id = #{sourceId} AND kpr.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    List<KnowledgePointRelation> selectBySourceAndSpace(@Param("sourceId") Long sourceId,
                                                        @Param("spaceId") Long spaceId,
                                                        @Param("ownerSubject") String ownerSubject);

    @Select("SELECT kpr.* FROM knowledge_point_relation kpr "
            + "JOIN learning_space ls ON ls.id = kpr.space_id "
            + "WHERE kpr.target_knowledge_point_id = #{targetId} AND kpr.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    List<KnowledgePointRelation> selectByTargetAndSpace(@Param("targetId") Long targetId,
                                                        @Param("spaceId") Long spaceId,
                                                        @Param("ownerSubject") String ownerSubject);

    @Select("SELECT kpr.* FROM knowledge_point_relation kpr "
            + "JOIN learning_space ls ON ls.id = kpr.space_id "
            + "WHERE kpr.id = #{relationId} AND kpr.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    KnowledgePointRelation selectByIdAndSpace(@Param("relationId") Long relationId,
                                              @Param("spaceId") Long spaceId,
                                              @Param("ownerSubject") String ownerSubject);
}
