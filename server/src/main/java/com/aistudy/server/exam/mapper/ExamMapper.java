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
}
