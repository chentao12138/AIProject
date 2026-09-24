package com.aistudy.server.knowledge.point.mapper;

import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.entity.KnowledgePointRelation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgePointMapper extends BaseMapper<KnowledgePoint> {

    @Select("SELECT kp.* FROM knowledge_point kp "
            + "JOIN learning_space ls ON ls.id = kp.space_id "
            + "WHERE kp.id = #{knowledgePointId} AND kp.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} AND kp.deleted_at IS NULL")
    KnowledgePoint selectByIdSpaceOwner(@Param("knowledgePointId") Long knowledgePointId,
                                        @Param("spaceId") Long spaceId,
                                        @Param("ownerSubject") String ownerSubject);

    @Select("SELECT kp.* FROM knowledge_point kp "
            + "JOIN learning_space ls ON ls.id = kp.space_id "
            + "WHERE kp.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "  AND kp.deleted_at IS NULL "
            + "ORDER BY kp.created_at DESC, kp.id DESC")
    List<KnowledgePoint> selectBySpaceOwner(@Param("spaceId") Long spaceId,
                                            @Param("ownerSubject") String ownerSubject);

    @Update("UPDATE knowledge_point SET status = #{status}, published_at = #{publishedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{knowledgePointId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int publishByIdAndSpace(@Param("knowledgePointId") Long knowledgePointId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("publishedAt") LocalDateTime publishedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_point SET title = #{title}, summary = #{summary}, content = #{content}, "
            + "difficulty = #{difficulty}, category_id = #{categoryId}, updated_at = #{updatedAt} "
            + "WHERE id = #{knowledgePointId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int updateDetailsByIdAndSpace(@Param("knowledgePointId") Long knowledgePointId,
                                  @Param("spaceId") Long spaceId,
                                  @Param("title") String title,
                                  @Param("summary") String summary,
                                  @Param("content") String content,
                                  @Param("difficulty") String difficulty,
                                  @Param("categoryId") Long categoryId,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_point SET status = #{status}, archived_at = #{archivedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{knowledgePointId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int archiveByIdAndSpace(@Param("knowledgePointId") Long knowledgePointId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("archivedAt") LocalDateTime archivedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_point SET status = #{status}, archived_at = NULL, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{knowledgePointId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int restoreByIdAndSpace(@Param("knowledgePointId") Long knowledgePointId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_point SET status = #{status}, rejected_at = #{rejectedAt}, "
            + "rejected_reason = #{rejectedReason}, updated_at = #{updatedAt} "
            + "WHERE id = #{knowledgePointId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int rejectByIdAndSpace(@Param("knowledgePointId") Long knowledgePointId,
                           @Param("spaceId") Long spaceId,
                           @Param("status") String status,
                           @Param("rejectedAt") LocalDateTime rejectedAt,
                           @Param("rejectedReason") String rejectedReason,
                           @Param("updatedAt") LocalDateTime updatedAt);

    @Update("INSERT INTO knowledge_point_relation (space_id, source_knowledge_point_id, target_knowledge_point_id, relation_type, weight, notes, created_at) "
            + "VALUES (#{spaceId}, #{sourceKnowledgePointId}, #{targetKnowledgePointId}, #{relationType}, #{weight}, #{notes}, NOW(6))")
    int insertRelation(@Param("spaceId") Long spaceId,
                       @Param("sourceKnowledgePointId") Long sourceKnowledgePointId,
                       @Param("targetKnowledgePointId") Long targetKnowledgePointId,
                       @Param("relationType") String relationType,
                       @Param("weight") Integer weight,
                       @Param("notes") String notes);

    @Select("SELECT kp.* FROM knowledge_point WHERE id = #{kpId} LIMIT 1")
    KnowledgePoint selectByIdAdmin(@Param("kpId") Long kpId);

    @Select("SELECT kp.* FROM knowledge_point kp "
            + "WHERE (#{spaceId} IS NULL OR kp.space_id = #{spaceId}) "
            + "  AND (#{status} IS NULL OR kp.status = #{status}) "
            + "  AND kp.deleted_at IS NULL "
            + "ORDER BY kp.created_at DESC, kp.id DESC")
    List<KnowledgePoint> selectAllAdmin(@Param("spaceId") Long spaceId,
                                        @Param("status") String status);

    @Update("UPDATE knowledge_point SET status = #{status}, published_at = #{publishedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{kpId} AND deleted_at IS NULL")
    int publishById(@Param("kpId") Long kpId,
                    @Param("status") String status,
                    @Param("publishedAt") LocalDateTime publishedAt,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_point SET status = #{status}, archived_at = #{archivedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{kpId} AND deleted_at IS NULL")
    int archiveById(@Param("kpId") Long kpId,
                    @Param("status") String status,
                    @Param("archivedAt") LocalDateTime archivedAt,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
