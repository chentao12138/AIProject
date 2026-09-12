package com.aistudy.server.ai.mapper;

import com.aistudy.server.ai.entity.AiConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * AI-002 — conversation persistence. All reads are space+user scoped.
 */
@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    @Select("SELECT * FROM ai_conversation "
            + "WHERE id = #{id} AND space_id = #{spaceId} AND user_subject = #{userSubject} "
            + "LIMIT 1")
    AiConversation selectByIdSpaceUser(@Param("id") Long id,
                                       @Param("spaceId") Long spaceId,
                                       @Param("userSubject") String userSubject);

    @Select("SELECT * FROM ai_conversation "
            + "WHERE space_id = #{spaceId} AND user_subject = #{userSubject} "
            + "AND status = #{status} "
            + "ORDER BY COALESCE(last_message_at, created_at) DESC, id DESC "
            + "LIMIT #{offset}, #{limit}")
    List<AiConversation> selectPageBySpaceUser(@Param("spaceId") Long spaceId,
                                               @Param("userSubject") String userSubject,
                                               @Param("status") String status,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_conversation "
            + "WHERE space_id = #{spaceId} AND user_subject = #{userSubject} "
            + "AND status = #{status}")
    long countBySpaceUserStatus(@Param("spaceId") Long spaceId,
                                @Param("userSubject") String userSubject,
                                @Param("status") String status);

    @Update("UPDATE ai_conversation SET status = #{status}, updated_at = #{now} "
            + "WHERE id = #{id} AND space_id = #{spaceId} AND user_subject = #{userSubject}")
    int updateStatus(@Param("id") Long id,
                     @Param("spaceId") Long spaceId,
                     @Param("userSubject") String userSubject,
                     @Param("status") String status,
                     @Param("now") java.time.LocalDateTime now);

    @Update("UPDATE ai_conversation SET last_message_at = #{now}, updated_at = #{now} "
            + "WHERE id = #{id} AND space_id = #{spaceId} AND user_subject = #{userSubject}")
    int touchLastMessageAt(@Param("id") Long id,
                           @Param("spaceId") Long spaceId,
                           @Param("userSubject") String userSubject,
                           @Param("now") java.time.LocalDateTime now);
}
