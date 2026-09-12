package com.aistudy.server.auth.mapper;

import com.aistudy.server.auth.entity.UserAccountRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * BUSINESS-019 — persistence for {@link UserAccountRole}.
 */
@Mapper
public interface UserAccountRoleMapper extends BaseMapper<UserAccountRole> {

    @Select("""
            SELECT id, user_account_id, role, created_at
              FROM user_account_role
             WHERE user_account_id = #{userAccountId}
            """)
    List<UserAccountRole> selectByUserAccountId(@Param("userAccountId") Long userAccountId);

    @Select("""
            SELECT COUNT(*)
              FROM user_account_role
             WHERE user_account_id = #{userAccountId}
               AND role = 'ADMIN'
            """)
    long countAdminByUserAccountId(@Param("userAccountId") Long userAccountId);

    @Select("""
            SELECT COUNT(DISTINCT uar.user_account_id)
              FROM user_account_role uar
             JOIN user_account ua ON ua.id = uar.user_account_id
             WHERE uar.role = 'ADMIN'
               AND ua.status = 'ACTIVE'
            """)
    long countActiveAdminAccounts();

    @Select("""
            SELECT id, user_account_id, role, created_at
              FROM user_account_role
             WHERE user_account_id = #{userAccountId}
               AND role IN ('USER','ADMIN')
               FOR UPDATE
            """)
    List<UserAccountRole> selectLockRolesByUserAccountId(@Param("userAccountId") Long userAccountId);

    @Select("""
            SELECT id, user_account_id, role, created_at
              FROM user_account_role
             WHERE role = 'ADMIN'
             FOR UPDATE
            """)
    List<UserAccountRole> selectAllAdminRolesForUpdate();
}
