package com.aistudy.server.question.mapper;

import com.aistudy.server.question.entity.Question;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    @Select("SELECT q.* FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "WHERE q.id = #{questionId} AND q.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} AND q.deleted_at IS NULL")
    Question selectByIdSpaceOwner(@Param("questionId") Long questionId,
                                  @Param("spaceId") Long spaceId,
                                  @Param("ownerSubject") String ownerSubject);

    /** Space-scoped lookup without owner JOIN (internal filters after parent check). */
    @Select("SELECT q.* FROM question q WHERE q.id = #{questionId} AND q.space_id = #{spaceId} AND q.deleted_at IS NULL")
    Question selectByIdAndSpace(@Param("questionId") Long questionId,
                                @Param("spaceId") Long spaceId);

    @Select("SELECT COUNT(*) FROM question_knowledge_point WHERE question_id = #{questionId} AND knowledge_point_id = #{knowledgePointId}")
    Integer countQuestionKnowledgePoint(@Param("questionId") Long questionId,
                                        @Param("knowledgePointId") Long knowledgePointId);

    @Select("SELECT COUNT(*) FROM question_knowledge_point qkp "
            + "JOIN knowledge_point kp ON kp.id = qkp.knowledge_point_id "
            + "WHERE qkp.question_id = #{questionId} AND kp.category_id = #{knowledgeCategoryId}")
    Integer countQuestionCategory(@Param("questionId") Long questionId,
                                  @Param("knowledgeCategoryId") Long knowledgeCategoryId);

    @Select("<script>"
            + "SELECT q.* FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "WHERE q.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "  AND q.deleted_at IS NULL "
            + "<if test='status != null'> AND q.status = #{status} </if>"
            + "<if test='questionType != null'> AND q.question_type = #{questionType} </if>"
            + "<if test='knowledgePointId != null'> AND EXISTS ("
            + "  SELECT 1 FROM question_knowledge_point qkp "
            + "  WHERE qkp.question_id = q.id AND qkp.knowledge_point_id = #{knowledgePointId})"
            + "</if>"
            + "ORDER BY q.created_at DESC, q.id DESC"
            + "</script>")
    List<Question> selectBySpaceOwnerFilters(@Param("spaceId") Long spaceId,
                                             @Param("ownerSubject") String ownerSubject,
                                             @Param("status") String status,
                                             @Param("questionType") String questionType,
                                             @Param("knowledgePointId") Long knowledgePointId);

    @Select("SELECT q.* FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "WHERE q.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "  AND q.status = #{status} AND q.deleted_at IS NULL "
            + "ORDER BY q.id ASC")
    List<Question> selectPublishedBySpaceOwner(@Param("spaceId") Long spaceId,
                                               @Param("ownerSubject") String ownerSubject,
                                               @Param("status") String status);

    @Select("<script>"
            + "SELECT DISTINCT q.* FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "JOIN question_knowledge_point qkp ON qkp.question_id = q.id "
            + "JOIN knowledge_point kp ON kp.id = qkp.knowledge_point_id "
            + "WHERE q.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "  AND q.status = #{status} AND q.deleted_at IS NULL "
            + "<if test='knowledgeCategoryId != null'> AND kp.category_id = #{knowledgeCategoryId} </if>"
            + "<if test='difficulty != null'> AND q.difficulty = #{difficulty} </if>"
            + "<if test='questionType != null'> AND q.question_type = #{questionType} </if>"
            + "ORDER BY q.id ASC"
            + "</script>")
    List<Question> selectPublishedByFilters(@Param("spaceId") Long spaceId,
                                            @Param("ownerSubject") String ownerSubject,
                                            @Param("status") String status,
                                            @Param("knowledgeCategoryId") Long knowledgeCategoryId,
                                            @Param("difficulty") String difficulty,
                                            @Param("questionType") String questionType);

    @Select("SELECT q.* FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "JOIN question_knowledge_point qkp ON qkp.question_id = q.id "
            + "WHERE q.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "  AND q.status = #{status} AND q.deleted_at IS NULL "
            + "  AND qkp.knowledge_point_id = #{knowledgePointId} "
            + "ORDER BY q.id ASC")
    List<Question> selectPublishedByKnowledgePointId(@Param("spaceId") Long spaceId,
                                                     @Param("ownerSubject") String ownerSubject,
                                                     @Param("knowledgePointId") Long knowledgePointId,
                                                     @Param("status") String status);

    @Update("UPDATE question SET status = #{status}, published_at = #{publishedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{questionId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int publishByIdAndSpace(@Param("questionId") Long questionId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("publishedAt") LocalDateTime publishedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE question SET stem = #{stem}, explanation = #{explanation}, "
            + "difficulty = #{difficulty}, updated_at = #{updatedAt} "
            + "WHERE id = #{questionId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int updateDetailsByIdAndSpace(@Param("questionId") Long questionId,
                                  @Param("spaceId") Long spaceId,
                                  @Param("stem") String stem,
                                  @Param("explanation") String explanation,
                                  @Param("difficulty") String difficulty,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE question SET status = #{status}, archived_at = #{archivedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{questionId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int archiveByIdAndSpace(@Param("questionId") Long questionId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("archivedAt") LocalDateTime archivedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE question SET status = #{status}, archived_at = NULL, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{questionId} AND space_id = #{spaceId} AND deleted_at IS NULL")
    int restoreByIdAndSpace(@Param("questionId") Long questionId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT q.* FROM question WHERE id = #{questionId} LIMIT 1")
    Question selectByIdAdmin(@Param("questionId") Long questionId);

    @Select("<script>"
            + "SELECT q.* FROM question q "
            + "WHERE q.deleted_at IS NULL "
            + "<if test='spaceId != null'> AND q.space_id = #{spaceId} </if>"
            + "<if test='status != null'> AND q.status = #{status} </if>"
            + "<if test='questionType != null'> AND q.question_type = #{questionType} </if>"
            + "ORDER BY q.created_at DESC, q.id DESC"
            + "</script>")
    List<Question> selectAllAdmin(@Param("spaceId") Long spaceId,
                                  @Param("status") String status,
                                  @Param("questionType") String questionType);
}
