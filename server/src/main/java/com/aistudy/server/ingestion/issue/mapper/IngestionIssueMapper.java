package com.aistudy.server.ingestion.issue.mapper;

import com.aistudy.server.ingestion.issue.entity.IngestionIssue;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IngestionIssueMapper extends BaseMapper<IngestionIssue> {

    @Select("SELECT ii.* FROM ingestion_issue ii "
            + "JOIN learning_space ls ON ls.id = ii.space_id "
            + "WHERE ii.space_id = #{spaceId} AND ii.ingestion_job_id = #{jobId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    List<IngestionIssue> selectBySpaceAndJob(@Param("spaceId") Long spaceId,
                                             @Param("jobId") Long jobId,
                                             @Param("ownerSubject") String ownerSubject);

    @Select("SELECT ii.* FROM ingestion_issue ii "
            + "JOIN learning_space ls ON ls.id = ii.space_id "
            + "WHERE ii.id = #{issueId} AND ii.space_id = #{spaceId} "
            + "  AND ls.owner_subject = #{ownerSubject}")
    IngestionIssue selectByIdAndSpace(@Param("issueId") Long issueId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("ownerSubject") String ownerSubject);

    @Update("UPDATE ingestion_issue SET status = #{status}, resolved_by = #{resolvedBy}, "
            + "resolved_at = #{resolvedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{issueId} AND space_id = #{spaceId}")
    int resolveByIdAndSpace(@Param("issueId") Long issueId,
                            @Param("spaceId") Long spaceId,
                            @Param("status") String status,
                            @Param("resolvedBy") Long resolvedBy,
                            @Param("resolvedAt") LocalDateTime resolvedAt,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE ingestion_issue SET status = #{status}, resolved_by = #{resolvedBy}, "
            + "resolved_at = #{resolvedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{issueId} AND space_id = #{spaceId}")
    int ignoreByIdAndSpace(@Param("issueId") Long issueId,
                           @Param("spaceId") Long spaceId,
                           @Param("status") String status,
                           @Param("resolvedBy") Long resolvedBy,
                           @Param("resolvedAt") LocalDateTime resolvedAt,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /** Issues raised for one source inside one space (ADMIN governance list). */
    @Select("SELECT ii.* FROM ingestion_issue ii "
            + "WHERE ii.source_id = #{sourceId} AND ii.space_id = #{spaceId} "
            + "ORDER BY ii.created_at DESC, ii.id DESC")
    List<IngestionIssue> selectBySpaceAndSource(@Param("spaceId") Long spaceId,
                                                @Param("sourceId") Long sourceId);
}
