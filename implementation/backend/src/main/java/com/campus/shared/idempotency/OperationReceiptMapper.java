package com.campus.shared.idempotency;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OperationReceiptMapper {

    @Select("""
            SELECT id, actor_id, action, idem_key, request_hash, result_ref, created_at
            FROM operation_receipts
            WHERE actor_id = #{actorId} AND action = #{action} AND idem_key = #{idemKey}
            """)
    OperationReceiptRow find(@Param("actorId") String actorId,
                             @Param("action") String action,
                             @Param("idemKey") String idemKey);

    @Insert("""
            INSERT INTO operation_receipts (id, actor_id, action, idem_key, request_hash, result_ref, created_at)
            VALUES (#{id}, #{actorId}, #{action}, #{idemKey}, #{requestHash}, #{resultRef}, #{createdAt})
            """)
    int insert(OperationReceiptRow row);
}
