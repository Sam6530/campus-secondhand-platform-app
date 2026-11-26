package com.campus.trade.order;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 唯一有效占用：reservations 主键为 item_id，数据库唯一约束阻止并发双下单。 */
@Mapper
public interface ReservationMapper {

    @Insert("""
            INSERT INTO reservations (item_id, order_id, created_at)
            VALUES (#{itemId}, #{orderId}, #{createdAt})
            """)
    int insert(@Param("itemId") String itemId,
               @Param("orderId") String orderId,
               @Param("createdAt") LocalDateTime createdAt);

    @Select("""
            SELECT COUNT(*) FROM reservations WHERE item_id = #{itemId}
            """)
    int countByItem(@Param("itemId") String itemId);

    @Select("""
            SELECT order_id FROM reservations WHERE item_id = #{itemId}
            """)
    String findOrderIdByItem(@Param("itemId") String itemId);

    @Delete("""
            DELETE FROM reservations WHERE order_id = #{orderId}
            """)
    int deleteByOrder(@Param("orderId") String orderId);
}
