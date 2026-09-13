package com.aistudy.server.auth.mapper;

import com.aistudy.server.auth.entity.RefreshSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * BUSINESS-018 — persistence for {@link RefreshSession}.
 *
 * <p>Only queries required by the refresh/logout flow are exposed.
 * There is no plaintext token column; callers must pass {@code tokenHash}
 * for lookup.
 */
@Mapper
public interface RefreshSessionMapper extends BaseMapper<RefreshSession> {

    @Select("""
            SELECT id, user_account_id, family_id, token_hash,
                   issued_at, expires_at, rotated_at, revoked_at,
                   replaced_by_id, revoke_reason, created_at, updated_at
              FROM auth_refresh_session
             WHERE token_hash = #{tokenHash}
               AND revoked_at IS NULL
               AND expires_at > #{now}
             LIMIT 1
            FOR UPDATE
            """)
    RefreshSession selectActiveByTokenHash(@Param("tokenHash") String tokenHash,
                                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE auth_refresh_session
               SET revoked_at = #{now},
                   revoke_reason = #{reason},
                   updated_at = #{now}
             WHERE family_id = #{familyId}
               AND revoked_at IS NULL
               AND expires_at > #{now}
               AND id <> #{excludeId}
            """)
    void revokeFamilyActive(@Param("familyId") long familyId,
                            @Param("now") LocalDateTime now,
                            @Param("reason") String reason,
                            @Param("excludeId") Long excludeId);

    @Update("""
            UPDATE auth_refresh_session
               SET revoked_at = #{now},
                   revoke_reason = #{reason},
                   updated_at = #{now}
             WHERE user_account_id = #{userAccountId}
               AND revoked_at IS NULL
               AND expires_at > #{now}
            """)
    void revokeAllActiveForUser(@Param("userAccountId") long userAccountId,
                                @Param("now") LocalDateTime now,
                                @Param("reason") String reason);
}
