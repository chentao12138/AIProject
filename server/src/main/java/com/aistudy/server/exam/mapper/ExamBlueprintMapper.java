package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.ExamBlueprint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ExamBlueprintMapper extends BaseMapper<ExamBlueprint> {

    @Select("SELECT eb.* FROM exam_blueprint eb "
            + "JOIN learning_space ls ON ls.id = eb.space_id "
            + "WHERE eb.id = #{blueprintId} AND eb.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} LIMIT 1")
    ExamBlueprint selectByIdSpaceOwner(@Param("blueprintId") Long blueprintId,
                                       @Param("spaceId") Long spaceId,
                                       @Param("ownerSubject") String ownerSubject);

    @Select("SELECT eb.* FROM exam_blueprint eb "
            + "JOIN learning_space ls ON ls.id = eb.space_id "
            + "WHERE eb.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY eb.created_at DESC, eb.id DESC")
    List<ExamBlueprint> selectBySpaceOwner(@Param("spaceId") Long spaceId,
                                           @Param("ownerSubject") String ownerSubject);

    @Update("UPDATE exam_blueprint SET title = #{title}, rules_json = #{rulesJson}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{blueprintId} AND space_id = #{spaceId}")
    int updateByIdAndSpace(@Param("blueprintId") Long blueprintId,
                           @Param("spaceId") Long spaceId,
                           @Param("title") String title,
                           @Param("rulesJson") String rulesJson,
                           @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE exam_blueprint SET status = #{status}, updated_at = #{updatedAt} "
            + "WHERE id = #{blueprintId} AND space_id = #{spaceId} AND status = #{fromStatus}")
    int updateStatusByIdAndSpace(@Param("blueprintId") Long blueprintId,
                                 @Param("spaceId") Long spaceId,
                                 @Param("fromStatus") String fromStatus,
                                 @Param("status") String status,
                                 @Param("updatedAt") LocalDateTime updatedAt);
}
