package com.campus.trade.order;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper {

    @Insert("""
            INSERT INTO orders (id, item_id, item_version, buyer_id, seller_id, buyer_public_ref, seller_public_ref,
                                stage, disputed, reminded, title, description, price, image_id, buyer_name,
                                seller_name, reason, created_at, completed_at)
            VALUES (#{id}, #{itemId}, #{itemVersion}, #{buyerId}, #{sellerId}, #{buyerPublicRef}, #{sellerPublicRef},
                    #{stage}, #{disputed}, #{reminded}, #{title}, #{description}, #{price}, #{imageId}, #{buyerName},
                    #{sellerName}, #{reason}, #{createdAt}, #{completedAt})
            """)
    int insert(OrderRow order);

    @Select("""
            SELECT id, item_id, item_version, buyer_id, seller_id, buyer_public_ref, seller_public_ref, stage,
                   disputed, reminded, title, description, price, image_id, buyer_name, seller_name, reason,
                   created_at, completed_at
            FROM orders
            WHERE id = #{id}
            """)
    OrderRow findById(@Param("id") String id);

    @Select("""
            SELECT id, item_id, item_version, buyer_id, seller_id, buyer_public_ref, seller_public_ref, stage,
                   disputed, reminded, title, description, price, image_id, buyer_name, seller_name, reason,
                   created_at, completed_at
            FROM orders
            WHERE id = #{id}
            FOR UPDATE
            """)
    OrderRow lockById(@Param("id") String id);

    /**
     * 只更新订单阶段与 B 负责的终止说明/完成时间。
     * disputed（处理中争议）属于治理标记（C 负责），这里不写，避免覆盖治理刚受理的争议。
     */
    @Update("""
            UPDATE orders
            SET stage = #{stage}, reason = #{reason}, completed_at = #{completedAt}
            WHERE id = #{id}
            """)
    int updateStage(OrderRow order);

    @Update("""
            UPDATE orders
            SET reminded = 1
            WHERE id = #{id}
            """)
    int markReminded(@Param("id") String id);

    @Select("""
            SELECT id, item_id, item_version, buyer_id, seller_id, buyer_public_ref, seller_public_ref, stage,
                   disputed, reminded, title, description, price, image_id, buyer_name, seller_name, reason,
                   created_at, completed_at
            FROM orders
            WHERE buyer_id = #{userId} OR seller_id = #{userId}
            ORDER BY created_at DESC, id DESC
            """)
    List<OrderRow> listForUser(@Param("userId") String userId);
}
