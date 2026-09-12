package com.aistudy.server.ai.mapper;

import com.aistudy.server.ai.entity.AiMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI-002 — message persistence. Reads are conversation-scoped; ownership
 * is enforced by joining through ai_conversation (space + user).
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    @Select("SELECT m.* FROM ai_message m "
            + "JOIN ai_conversation c ON c.id = m.conversation_id "
            + "WHERE m.conversation_id = #{conversationId} "
            + "AND c.space_id = #{spaceId} AND c.user_subject = #{userSubject} "
            + "ORDER BY m.created_at ASC, m.id ASC "
            + "LIMIT #{offset}, #{limit}")
    List<AiMessage> selectPageByConversation(@Param("conversationId") Long conversationId,
                                             @Param("spaceId") Long spaceId,
                                             @Param("userSubject") String userSubject,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_message m "
            + "JOIN ai_conversation c ON c.id = m.conversation_id "
            + "WHERE m.conversation_id = #{conversationId} "
            + "AND c.space_id = #{spaceId} AND c.user_subject = #{userSubject}")
    long countByConversation(@Param("conversationId") Long conversationId,
                             @Param("spaceId") Long spaceId,
                             @Param("userSubject") String userSubject);

    /**
     * Newest N messages for provider history; caller reverses to chronological.
     */
    @Select("SELECT * FROM ("
            + "  SELECT m.* FROM ai_message m "
            + "  JOIN ai_conversation c ON c.id = m.conversation_id "
            + "  WHERE m.conversation_id = #{conversationId} "
            + "  AND c.space_id = #{spaceId} AND c.user_subject = #{userSubject} "
            + "  ORDER BY m.created_at DESC, m.id DESC "
            + "  LIMIT #{limit}"
            + ") recent ORDER BY created_at ASC, id ASC")
    List<AiMessage> selectRecentForHistory(@Param("conversationId") Long conversationId,
                                           @Param("spaceId") Long spaceId,
                                           @Param("userSubject") String userSubject,
                                           @Param("limit") int limit);
}
