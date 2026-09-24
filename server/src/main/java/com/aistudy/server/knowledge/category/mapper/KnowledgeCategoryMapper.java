package com.aistudy.server.knowledge.category.mapper;

import com.aistudy.server.knowledge.category.entity.KnowledgeCategory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-003 — production MyBatis-Plus mapper for
 * {@link KnowledgeCategory}.
 *
 * <p>{@link BaseMapper} is used ONLY for insert. Every business READ
 * is an explicit owner-scoped {@code @Select} that JOINs
 * {@code learning_space} and constrains {@code ls.owner_subject}:
 *
 * <ul>
 *   <li>{@link #selectByIdAndSpaceAndOwner} — single category:
 *       {@code WHERE c.id = ? AND c.space_id = ? AND
 *       ls.owner_subject = ?}. Used for detail AND for the
 *       parent-validation lookup, so a parentId pointing at another
 *       space (or another owner) returns {@code null}.</li>
 *   <li>{@link #selectBySpaceAndOwner} — list all categories of one
 *       owned space, ordered {@code sort_order ASC, id ASC} (tree
 *       presentation order).</li>
 * </ul>
 *
 * <p>The inherited unscoped BaseMapper reads ({@code selectById},
 * {@code selectList}, {@code selectOne}) are NEVER used — they carry
 * no space/owner predicate. Only
 * {@link com.aistudy.server.knowledge.category.service.KnowledgeCategoryService}
 * calls this mapper.
 */
@Mapper
public interface KnowledgeCategoryMapper extends BaseMapper<KnowledgeCategory> {

    /**
     * Returns the category iff it belongs to {@code spaceId} AND that
     * space is owned by {@code ownerSubject}. Any mismatch returns
     * {@code null} (404 semantics, anti-probing).
     */
    @Select("SELECT c.* "
            + "FROM knowledge_category c "
            + "JOIN learning_space ls ON ls.id = c.space_id "
            + "WHERE c.id = #{categoryId} "
            + "  AND c.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    KnowledgeCategory selectByIdAndSpaceAndOwner(@Param("categoryId") Long categoryId,
                                                 @Param("spaceId") Long spaceId,
                                                 @Param("ownerSubject") String ownerSubject);

    /**
     * Returns all categories of one owned space in tree-presentation
     * order (sort_order ASC, id ASC). The owner predicate is enforced
     * IN SQL via the JOIN — this method can never list categories of
     * an unowned space even if a caller bypasses the service layer.
     */
    @Select("SELECT c.* FROM knowledge_category c "
            + "JOIN learning_space ls ON ls.id = c.space_id "
            + "WHERE c.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY c.sort_order ASC, c.id ASC")
    List<KnowledgeCategory> selectBySpaceAndOwner(@Param("spaceId") Long spaceId,
                                                  @Param("ownerSubject") String ownerSubject);

    @Select("SELECT c.* FROM knowledge_category c "
            + "JOIN learning_space ls ON ls.id = c.space_id "
            + "WHERE c.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY c.sort_order ASC, c.id ASC")
    List<KnowledgeCategory> selectByOwnerIncludingDeleted(@Param("spaceId") Long spaceId,
                                                          @Param("ownerSubject") String ownerSubject);

    @Update("UPDATE knowledge_category SET name = #{name}, description = #{description}, "
            + "sort_order = #{sortOrder}, updated_at = #{updatedAt} "
            + "WHERE id = #{categoryId} AND space_id = #{spaceId}")
    int updateDetailsByIdAndSpace(@Param("categoryId") Long categoryId,
                                  @Param("spaceId") Long spaceId,
                                  @Param("name") String name,
                                  @Param("description") String description,
                                  @Param("sortOrder") Integer sortOrder,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_category SET parent_id = #{parentId}, updated_at = #{updatedAt} "
            + "WHERE id = #{categoryId} AND space_id = #{spaceId}")
    int reparentByIdAndSpace(@Param("categoryId") Long categoryId,
                             @Param("spaceId") Long spaceId,
                             @Param("parentId") Long parentId,
                             @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_category SET sort_order = #{sortOrder}, updated_at = #{updatedAt} "
            + "WHERE id = #{categoryId} AND space_id = #{spaceId}")
    int updateSortOrderByIdAndSpace(@Param("categoryId") Long categoryId,
                                    @Param("spaceId") Long spaceId,
                                    @Param("sortOrder") Integer sortOrder,
                                    @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_category SET deleted_at = #{deletedAt} "
            + "WHERE id = #{categoryId} AND space_id = #{spaceId}")
    int softDeleteByIdAndSpace(@Param("categoryId") Long categoryId,
                               @Param("spaceId") Long spaceId,
                               @Param("deletedAt") LocalDateTime deletedAt);

    @Select("SELECT COUNT(*) FROM knowledge_category WHERE parent_id = #{parentId}")
    long countByParentId(@Param("parentId") Long parentId);

    @Select("SELECT c.* FROM knowledge_category WHERE id = #{categoryId} LIMIT 1")
    KnowledgeCategory selectByIdAdmin(@Param("categoryId") Long categoryId);

    @Select("SELECT c.* FROM knowledge_category "
            + "WHERE (#{spaceId} IS NULL OR space_id = #{spaceId}) "
            + "ORDER BY created_at DESC, id DESC")
    List<KnowledgeCategory> selectBySpace(@Param("spaceId") Long spaceId);

    @Update("UPDATE knowledge_category SET status = #{status}, published_at = #{publishedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{categoryId}")
    int publishById(@Param("categoryId") Long categoryId,
                    @Param("status") String status,
                    @Param("publishedAt") LocalDateTime publishedAt,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_category SET status = #{status}, archived_at = #{archivedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{categoryId}")
    int archiveById(@Param("categoryId") Long categoryId,
                    @Param("status") String status,
                    @Param("archivedAt") LocalDateTime archivedAt,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
