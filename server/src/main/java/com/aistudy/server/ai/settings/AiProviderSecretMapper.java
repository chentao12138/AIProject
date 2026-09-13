package com.aistudy.server.ai.settings;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI-009 — per-user encrypted secret.
 */
@Mapper
public interface AiProviderSecretMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<AiProviderSecret> {

    @Select("SELECT * FROM ai_provider_secret WHERE user_subject = #{userSubject} LIMIT 1")
    AiProviderSecret selectByUserSubject(@Param("userSubject") String userSubject);
}
