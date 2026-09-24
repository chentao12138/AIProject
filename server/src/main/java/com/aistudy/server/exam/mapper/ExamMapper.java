package com.aistudy.server.exam.mapper;

import com.aistudy.server.exam.entity.Exam;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-012 — mapper for {@link Exam}.
 *
 * <p>Owner-scoped reads via JOIN learning_space; BaseMapper for
 * insert only.
 */
@Mapper
public interface ExamMapper extends BaseMapper<Exam> {

    @Select("SELECT e.* FROM exam e "
            + "JOIN learning_space ls ON ls.id = e.space_id "
            + "WHERE e.id = #{examId} AND e.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    Exam selectByIdSpaceOwner(@Param("examId") Long examId,
                              @Param("spaceId") Long spaceId,
                              @Param("ownerSubject") String ownerSubject);

    @Select("SELECT e.* FROM exam e "
            + "JOIN learning_space ls ON ls.id = e.space_id "
            + "WHERE e.space_id = #{spaceId} AND ls.owner_subject = #{ownerSubject} "
            + "ORDER BY e.created_at DESC, e.id DESC")
    List<Exam> selectBySpaceOwner(@Param("spaceId") Long spaceId,
                                  @Param("ownerSubject") String ownerSubject);

    @Update("UPDATE exam SET status = #{status}, published_at = #{publishedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{examId} AND space_id = #{spaceId} AND status = #{fromStatus}")
    int publishByIdAndSpace(@Param("examId") Long examId,
                            @Param("spaceId") Long spaceId,
                            @Param("fromStatus") String fromStatus,
                            @Param("status") String status,
                            @Param("publishedAt") LocalDateTime publishedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE exam SET title = #{title}, description = #{description}, "
            + "time_limit_minutes = #{timeLimitMinutes}, updated_at = #{updatedAt} "
            + "WHERE id = #{examId} AND space_id = #{spaceId} AND status = 'DRAFT'")
    int updateDetailsByIdAndSpace(@Param("examId") Long examId,
                                  @Param("spaceId") Long spaceId,
                                  @Param("title") String title,
                                  @Param("description") String description,
                                  @Param("timeLimitMinutes") Integer timeLimitMinutes,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE exam SET total_score = #{totalScore}, updated_at = #{updatedAt} "
            + "WHERE id = #{examId} AND space_id = #{spaceId}")
    int updateTotalScoreByIdAndSpace(@Param("examId") Long examId,
                                     @Param("spaceId") Long spaceId,
                                     @Param("totalScore") Integer totalScore,
                                     @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE exam SET status = #{status}, archived_at = #{archivedAt}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{examId} AND space_id = #{spaceId}")
    int archiveByIdAndSpace(@Param("examId") Long examId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("archivedAt") LocalDateTime archivedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT e.* FROM exam WHERE id = #{examId} LIMIT 1")
    Exam selectByIdAdmin(@Param("examId") Long examId);

    @Select("<script>"
            + "SELECT e.* FROM exam e "
            + "WHERE 1=1 "
            + "<if test='spaceId != null'> AND e.space_id = #{spaceId} </if>"
            + "<if test='status != null'> AND e.status = #{status} </if>"
            + "ORDER BY e.created_at DESC, e.id DESC"
            + "</script>")
    List<Exam> selectAllAdmin(@Param("spaceId") Long spaceId,
                              @Param("status") String status);
}
