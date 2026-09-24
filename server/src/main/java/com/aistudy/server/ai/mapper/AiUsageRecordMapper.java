package com.aistudy.server.ai.mapper;

import com.aistudy.server.ai.entity.AiUsageRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiUsageRecordMapper extends BaseMapper<AiUsageRecord> {

    @Insert("INSERT INTO ai_usage_record "
            + "(user_subject, space_id, provider, model, purpose, request_id, "
            + " prompt_tokens, completion_tokens, total_tokens, latency_ms, status, error_code, created_at) "
            + "VALUES (#{userSubject}, #{spaceId}, #{provider}, #{model}, #{purpose}, #{requestId}, "
            + " #{promptTokens}, #{completionTokens}, #{totalTokens}, #{latencyMs}, #{status}, #{errorCode}, #{createdAt})")
    void insertRecord(AiUsageRecord record);

    @Select("SELECT * FROM ai_usage_record "
            + "WHERE user_subject = #{userSubject} "
            + "ORDER BY created_at DESC, id DESC "
            + "LIMIT #{limit} OFFSET #{offset}")
    List<AiUsageRecord> selectByUser(@Param("userSubject") String userSubject,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_usage_record WHERE user_subject = #{userSubject}")
    long countByUser(String userSubject);
}
