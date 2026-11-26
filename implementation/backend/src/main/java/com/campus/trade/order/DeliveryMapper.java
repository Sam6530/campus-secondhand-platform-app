package com.campus.trade.order;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeliveryMapper {

    @Insert("""
            INSERT INTO deliveries (id, order_id, actor_id, note, created_at)
            VALUES (#{id}, #{orderId}, #{actorId}, #{note}, #{createdAt})
            """)
    int insert(DeliveryRow row);

    @Select("""
            SELECT id, order_id, actor_id, note, created_at
            FROM deliveries
            WHERE order_id = #{orderId}
            ORDER BY created_at ASC, id ASC
            """)
    List<DeliveryRow> listByOrder(@Param("orderId") String orderId);
}
