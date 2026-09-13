package com.aistudy.server.ai.settings;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI-009 — per-user runtime settings.
 */
@Mapper
public interface AiProviderSettingsMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<AiProviderSettings> {

    @Select("SELECT * FROM ai_provider_settings WHERE user_subject = #{userSubject} LIMIT 1")
    AiProviderSettings selectByUserSubject(@Param("userSubject") String userSubject);
}
