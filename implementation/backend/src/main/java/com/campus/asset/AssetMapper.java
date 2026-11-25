package com.campus.asset;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetMapper {

    @Insert("""
            INSERT INTO assets (id, uploader_id, kind, storage_key, content_type, size_bytes, created_at)
            VALUES (#{id}, #{uploaderId}, #{kind}, #{storageKey}, #{contentType}, #{sizeBytes}, #{createdAt})
            """)
    int insert(AssetRow row);

    @Select("""
            SELECT id, uploader_id, kind, storage_key, content_type, size_bytes, created_at
            FROM assets
            WHERE id = #{id}
            """)
    AssetRow findById(@Param("id") String id);

    /**
     * 该图片是否被当前公开可交易的物品版本引用（与市场同一规则）：
     * 在售、未隐藏、物主未受限、无有效占用、当前版本审核通过。
     */
    @Select("""
            SELECT COUNT(*)
            FROM items i
            JOIN item_versions v ON v.item_id = i.id AND v.version = i.current_version
            JOIN accounts a ON a.id = i.seller_id
            LEFT JOIN reservations r ON r.item_id = i.id
            WHERE v.image_id = #{assetId}
              AND i.status = '在售'
              AND i.hidden = 0
              AND a.restricted = 0
              AND r.item_id IS NULL
              AND v.review_status = '通过'
            """)
    int countPublicItemUsage(@Param("assetId") String assetId);

    /** 订单快照引用的历史图片仍可被交易当事人读取，不受物品当前是否公开影响。 */
    @Select("""
            SELECT COUNT(*)
            FROM orders o
            WHERE o.image_id = #{assetId}
              AND (o.buyer_id = #{userId} OR o.seller_id = #{userId})
            """)
    int countParticipantOrderUsage(@Param("assetId") String assetId, @Param("userId") String userId);
}
