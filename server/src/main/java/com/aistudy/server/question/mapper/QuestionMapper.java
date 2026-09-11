package com.aistudy.server.question.mapper;

import com.aistudy.server.question.entity.Question;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-008 — production MyBatis-Plus mapper for {@link Question}.
 *
 * <p>{@link BaseMapper} is used ONLY for insert. All business reads
 * are explicit owner-scoped {@code @Select}s that JOIN
 * {@code learning_space} (owner_subject) and filter
 * {@code deleted_at IS NULL}.
 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    @Select("SELECT q.* "
            + "FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "WHERE q.id = #{questionId} "
            + "  AND q.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND q.deleted_at IS NULL")
    Question selectByIdSpaceOwner(@Param("questionId") Long questionId,
                                  @Param("spaceId") Long spaceId,
                                  @Param("ownerSubject") String ownerSubject);

    /**
     * Filtered listing of non-deleted questions of one owned space.
     * All filters optional; knowledgePointId applies an EXISTS on the
     * M:N table. Newest first (stable tie-break by id DESC).
     */
    @Select("<script>"
            + "SELECT q.* FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "WHERE q.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
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

    /**
     * Lists all PUBLISHED non-deleted questions of an owned space
     * (practice/exam selection source). Stable order: id ASC.
     */
    @Select("SELECT q.* FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "WHERE q.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND q.status = #{status} "
            + "  AND q.deleted_at IS NULL "
            + "ORDER BY q.id ASC")
    List<Question> selectPublishedBySpaceOwner(@Param("spaceId") Long spaceId,
                                               @Param("ownerSubject") String ownerSubject,
                                               @Param("status") String status);

    /**
     * PUBLISHED non-deleted questions of an owned space linked to ONE
     * knowledge point (practice auto-selection source). Stable
     * deterministic order: question.id ASC.
     */
    @Select("SELECT q.* FROM question q "
            + "JOIN learning_space ls ON ls.id = q.space_id "
            + "JOIN question_knowledge_point qkp ON qkp.question_id = q.id "
            + "WHERE q.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND q.status = #{status} "
            + "  AND q.deleted_at IS NULL "
            + "  AND qkp.knowledge_point_id = #{knowledgePointId} "
            + "ORDER BY q.id ASC")
    List<Question> selectPublishedByKnowledgePointId(@Param("spaceId") Long spaceId,
                                                     @Param("ownerSubject") String ownerSubject,
                                                     @Param("knowledgePointId") Long knowledgePointId,
                                                     @Param("status") String status);

    @Update("UPDATE question "
            + "SET status = #{status}, "
            + "    published_at = #{publishedAt}, "
            + "    updated_at = #{updatedAt} "
            + "WHERE id = #{questionId} "
            + "  AND space_id = #{spaceId} "
            + "  AND deleted_at IS NULL")
    int publishByIdAndSpace(@Param("questionId") Long questionId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("publishedAt") LocalDateTime publishedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);
}
