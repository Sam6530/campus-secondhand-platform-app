package com.campus.shared.audit;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditMapper {

    @Insert("""
            INSERT INTO audit_facts (id, actor_id, object_type, object_id, action, from_state, to_state,
                                     reason, result, created_at)
            VALUES (#{id}, #{actorId}, #{objectType}, #{objectId}, #{action}, #{fromState}, #{toState},
                    #{reason}, #{result}, #{createdAt})
            """)
    int insert(AuditRow row);

    @Select("""
            SELECT id, actor_id, object_type, object_id, action, from_state, to_state, reason, result, created_at
            FROM audit_facts
            WHERE object_type = #{objectType} AND object_id = #{objectId}
            ORDER BY created_at ASC, id ASC
            """)
    List<AuditRow> findByObject(@Param("objectType") String objectType, @Param("objectId") String objectId);
}
