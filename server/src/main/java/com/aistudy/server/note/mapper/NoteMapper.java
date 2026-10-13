package com.aistudy.server.note.mapper;

import com.aistudy.server.note.entity.Note;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    /** Notes owned by the authenticated subject in the given space. */
    @Select("SELECT n.* FROM note n "
            + "JOIN learning_space ls ON ls.id = n.space_id "
            + "WHERE n.space_id = #{spaceId} AND n.user_subject_id = #{userSubject} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY n.created_at DESC, n.id DESC")
    List<Note> selectBySpaceAndUser(@Param("spaceId") Long spaceId,
                                    @Param("userSubject") String userSubject,
                                    @Param("ownerSubject") String ownerSubject);

    @Select("SELECT n.* FROM note n "
            + "JOIN learning_space ls ON ls.id = n.space_id "
            + "WHERE n.id = #{noteId} AND n.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject} "
            + "  AND n.user_subject_id = #{ownerSubject}")
    Note selectByIdAndSpaceAndOwner(@Param("noteId") Long noteId,
                                    @Param("spaceId") Long spaceId,
                                    @Param("ownerSubject") String ownerSubject);

    /**
     * Shared WHERE fragment of {@link #selectPageByFilter} and
     * {@link #countByFilter}. An entity filter matches the note's primary
     * context pointer OR its M:N link row, because a note can be attached to
     * several knowledge points and blocks while naming only one of them in
     * {@code knowledge_point_id}.
     */
    String FILTER_WHERE =
            "<choose>"
            + "<when test='q.archived != null and q.archived'>AND n.archived_at IS NOT NULL </when>"
            + "<otherwise>AND n.archived_at IS NULL </otherwise>"
            + "</choose>"
            + "<if test='q.keywordLike != null'>"
            + "  AND (n.title LIKE #{q.keywordLike} OR n.content LIKE #{q.keywordLike})"
            + "</if> "
            + "<if test='q.knowledgePointId != null'>"
            + "  AND (n.knowledge_point_id = #{q.knowledgePointId} "
            + "       OR n.id IN (SELECT nk.note_id FROM note_knowledge_point nk "
            + "                   WHERE nk.space_id = #{q.spaceId} "
            + "                     AND nk.knowledge_point_id = #{q.knowledgePointId}))"
            + "</if> "
            + "<if test='q.contentBlockId != null'>"
            + "  AND (n.content_block_id = #{q.contentBlockId} "
            + "       OR n.id IN (SELECT ns.note_id FROM note_source ns "
            + "                   WHERE ns.space_id = #{q.spaceId} "
            + "                     AND ns.content_block_id = #{q.contentBlockId}))"
            + "</if> "
            + "<if test='q.sourceId != null'>"
            + "  AND (n.source_id = #{q.sourceId} "
            + "       OR n.id IN (SELECT ns2.note_id FROM note_source ns2 "
            + "                   JOIN content_block cb ON cb.id = ns2.content_block_id "
            + "                   WHERE ns2.space_id = #{q.spaceId} AND cb.source_id = #{q.sourceId}))"
            + "</if> "
            + "<if test='q.questionId != null'>"
            + "  AND n.question_id = #{q.questionId}"
            + "</if>";

    /**
     * One page of the filtered notes, newest change first. Omitted filters are
     * skipped, so one statement serves the plain list, the archive tab and the
     * entity → notes direction of checklist A8.3.
     */
    @Select("<script>"
            + "SELECT n.* FROM note n "
            + "JOIN learning_space ls ON ls.id = n.space_id "
            + "WHERE n.space_id = #{q.spaceId} AND n.user_subject_id = #{q.ownerSubject} "
            + "  AND ls.owner_subject = #{q.ownerSubject} "
            + FILTER_WHERE + " "
            + "ORDER BY n.updated_at DESC, n.id DESC "
            + "LIMIT #{q.limit} OFFSET #{q.offset}"
            + "</script>")
    List<Note> selectPageByFilter(@Param("q") NoteListQuery query);

    /** Total rows for {@link #selectPageByFilter}: same filter, no window. */
    @Select("<script>"
            + "SELECT COUNT(1) FROM note n "
            + "JOIN learning_space ls ON ls.id = n.space_id "
            + "WHERE n.space_id = #{q.spaceId} AND n.user_subject_id = #{q.ownerSubject} "
            + "  AND ls.owner_subject = #{q.ownerSubject} "
            + FILTER_WHERE
            + "</script>")
    long countByFilter(@Param("q") NoteListQuery query);

    /**
     * Full document write, context pointers included, so a null pointer really
     * clears the column.
     *
     * <p>{@code updateById} cannot serve this: MyBatis-Plus skips null fields by
     * default, which made {@code NoteService.update} report a cleared context
     * while the row kept the old one.
     */
    @Update("UPDATE note SET title = #{title}, content = #{content}, "
            + "content_format = #{contentFormat}, "
            + "source_id = #{sourceId}, source_page_id = #{sourcePageId}, "
            + "content_block_id = #{contentBlockId}, "
            + "knowledge_point_id = #{knowledgePointId}, question_id = #{questionId}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{noteId} AND space_id = #{spaceId} "
            + "  AND user_subject_id = #{ownerSubject}")
    int updateDocument(@Param("noteId") Long noteId,
                       @Param("spaceId") Long spaceId,
                       @Param("ownerSubject") String ownerSubject,
                       @Param("title") String title,
                       @Param("content") String content,
                       @Param("contentFormat") String contentFormat,
                       @Param("sourceId") Long sourceId,
                       @Param("sourcePageId") Long sourcePageId,
                       @Param("contentBlockId") Long contentBlockId,
                       @Param("knowledgePointId") Long knowledgePointId,
                       @Param("questionId") Long questionId,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** Archive and restore share one statement so {@code NULL} is written, not skipped. */
    @Update("UPDATE note SET archived_at = #{archivedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{noteId} AND space_id = #{spaceId} "
            + "  AND user_subject_id = #{ownerSubject}")
    int updateArchivedAt(@Param("noteId") Long noteId,
                         @Param("spaceId") Long spaceId,
                         @Param("ownerSubject") String ownerSubject,
                         @Param("archivedAt") LocalDateTime archivedAt,
                         @Param("updatedAt") LocalDateTime updatedAt);
}
