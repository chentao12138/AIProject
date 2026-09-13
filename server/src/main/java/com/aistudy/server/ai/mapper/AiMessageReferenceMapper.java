package com.aistudy.server.ai.mapper;

import com.aistudy.server.ai.entity.AiMessageReference;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI-005 — message reference persistence. Reads are conversation+user scoped.
 */
@Mapper
public interface AiMessageReferenceMapper extends BaseMapper<AiMessageReference> {

    @Select("<script>"
            + "SELECT r.* FROM ai_message_reference r "
            + "JOIN ai_message m ON m.id = r.message_id "
            + "JOIN ai_conversation c ON c.id = m.conversation_id "
            + "WHERE c.id = #{conversationId} "
            + "  AND c.space_id = #{spaceId} "
            + "  AND c.user_subject = #{userSubject} "
            + "  AND r.message_id IN "
            + "<foreach item='id' collection='messageIds' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY r.message_id ASC, r.ordinal ASC"
            + "</script>")
    List<AiMessageReference> selectByConversationMessages(
            @Param("conversationId") Long conversationId,
            @Param("spaceId") Long spaceId,
            @Param("userSubject") String userSubject,
            @Param("messageIds") List<Long> messageIds);
}
