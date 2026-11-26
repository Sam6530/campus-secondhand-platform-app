package com.campus.shared.account;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountMapper {

    @Select("""
            SELECT id, public_ref, username, nickname, role, verified, restricted, created_at
            FROM accounts
            WHERE id = #{id}
            """)
    AccountRow findById(@Param("id") String id);

    @Select("""
            SELECT id, public_ref, username, nickname, role, verified, restricted, created_at
            FROM accounts
            WHERE public_ref = #{publicRef}
            """)
    AccountRow findByPublicRef(@Param("publicRef") String publicRef);
}
