package com.aistudy.server.auth.mapper;

import com.aistudy.server.auth.entity.UserAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BUSINESS-017 — production MyBatis mapper for {@link UserAccount}.
 *
 * <p>Only {@code selectByUsername} is required for the login flow.
 * The query returns a single account row; callers are responsible for
 * not exposing {@code passwordHash} outside the authentication path.
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    @Select("SELECT id, subject, username, password_hash, status, created_at, updated_at "
            + "FROM user_account WHERE username = #{username} LIMIT 1")
    UserAccount selectByUsername(@Param("username") String username);

    @Select("SELECT id, subject, username, password_hash, status, created_at, updated_at "
            + "FROM user_account WHERE subject = #{subject} LIMIT 1")
    UserAccount selectBySubject(@Param("subject") String subject);

    @Select("SELECT id, subject, username, password_hash, status, created_at, updated_at "
            + "FROM user_account ORDER BY created_at DESC, id DESC LIMIT #{offset}, #{limit}")
    List<UserAccount> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM user_account")
    long countAll();
}
