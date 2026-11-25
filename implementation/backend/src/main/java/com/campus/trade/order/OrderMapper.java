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

    @Update("""
            UPDATE orders
            SET stage = #{stage}, disputed = #{disputed}, reminded = #{reminded}, reason = #{reason},
                completed_at = #{completedAt}
            WHERE id = #{id}
            """)
    int update(OrderRow order);

    @Select("""
            SELECT id, item_id, item_version, buyer_id, seller_id, buyer_public_ref, seller_public_ref, stage,
                   disputed, reminded, title, description, price, image_id, buyer_name, seller_name, reason,
                   created_at, completed_at
            FROM orders
            WHERE buyer_id = #{userId} OR seller_id = #{userId}
            ORDER BY created_at DESC, id DESC
            """)
    List<OrderRow> listForUser(@Param("userId") String userId);

    @Select("""
            SELECT COUNT(*)
            FROM orders
            WHERE item_id = #{itemId}
            """)
    int countByItem(@Param("itemId") String itemId);
}
