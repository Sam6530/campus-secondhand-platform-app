package com.campus.trade.item;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ItemMapper {

    @Insert("""
            INSERT INTO items (id, seller_id, status, current_version, image_id, hidden, relist_allowed,
                               created_at, updated_at)
            VALUES (#{id}, #{sellerId}, #{status}, #{currentVersion}, #{imageId}, #{hidden}, #{relistAllowed},
                    #{createdAt}, #{updatedAt})
            """)
    int insertItem(ItemRow item);

    @Insert("""
            INSERT INTO item_versions (id, item_id, version, title, category, description, price, image_id,
                                       review_status, review_reason, reviewed_by, reviewed_at, created_at)
            VALUES (#{id}, #{itemId}, #{version}, #{title}, #{category}, #{description}, #{price}, #{imageId},
                    #{reviewStatus}, #{reviewReason}, #{reviewedBy}, #{reviewedAt}, #{createdAt})
            """)
    int insertVersion(ItemVersionRow version);

    @Select("""
            SELECT id, seller_id, status, current_version, image_id, hidden, relist_allowed, created_at, updated_at
            FROM items
            WHERE id = #{id}
            """)
    ItemRow findById(@Param("id") String id);

    @Select("""
            SELECT id, seller_id, status, current_version, image_id, hidden, relist_allowed, created_at, updated_at
            FROM items
            WHERE id = #{id}
            FOR UPDATE
            """)
    ItemRow lockById(@Param("id") String id);

    @Update("""
            UPDATE items
            SET status = #{status}, current_version = #{currentVersion}, image_id = #{imageId},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateVersionPointer(ItemRow item);

    /**
     * 只更新生命周期状态。hidden/relist_allowed 属于治理标记（C 负责），
     * 这里不写，避免把治理刚设置的隐藏/限制覆盖回旧值。
     */
    @Update("""
            UPDATE items
            SET status = #{status}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateStatus(ItemRow item);

    @Select("""
            SELECT id, item_id, version, title, category, description, price, image_id,
                   review_status, review_reason, reviewed_by, reviewed_at, created_at
            FROM item_versions
            WHERE item_id = #{itemId} AND version = #{version}
            """)
    ItemVersionRow findVersion(@Param("itemId") String itemId, @Param("version") int version);

    @Select("""
            SELECT id, item_id, version, title, category, description, price, image_id,
                   review_status, review_reason, reviewed_by, reviewed_at, created_at
            FROM item_versions
            WHERE item_id = #{itemId}
            ORDER BY version DESC
            LIMIT 1
            """)
    ItemVersionRow findCurrentVersion(@Param("itemId") String itemId);

    @Update("""
            UPDATE item_versions
            SET review_status = #{reviewStatus}, review_reason = #{reviewReason},
                reviewed_by = #{reviewedBy}, reviewed_at = #{reviewedAt}
            WHERE item_id = #{itemId} AND version = #{version}
            """)
    int updateVersionReview(ItemVersionRow version);

    @Select("""
            <script>
            SELECT i.id, i.seller_id, a.public_ref AS seller_ref, a.nickname AS seller_name,
                   a.verified AS seller_verified, v.title, v.category, v.description, v.price, v.image_id,
                   v.version, v.review_status, v.review_reason, i.status, i.hidden, i.relist_allowed, i.created_at
            FROM items i
            JOIN accounts a ON a.id = i.seller_id
            JOIN item_versions v ON v.item_id = i.id AND v.version = i.current_version
            LEFT JOIN reservations r ON r.item_id = i.id
            WHERE i.status = '在售'
              AND i.hidden = 0
              AND a.restricted = 0
              AND r.item_id IS NULL
              AND v.review_status = '通过'
            <if test="q != null and q != ''">
              AND (v.title LIKE CONCAT('%', #{q}, '%') OR v.description LIKE CONCAT('%', #{q}, '%'))
            </if>
            <if test="category != null and category != ''">
              AND v.category = #{category}
            </if>
            ORDER BY i.created_at DESC, i.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ItemViewRow> searchPublic(@Param("q") String q,
                                   @Param("category") String category,
                                   @Param("limit") int limit,
                                   @Param("offset") long offset);

    @Select("""
            SELECT i.id, i.seller_id, a.public_ref AS seller_ref, a.nickname AS seller_name,
                   a.verified AS seller_verified, v.title, v.category, v.description, v.price, v.image_id,
                   v.version, v.review_status, v.review_reason, i.status, i.hidden, i.relist_allowed, i.created_at
            FROM items i
            JOIN accounts a ON a.id = i.seller_id
            JOIN item_versions v ON v.item_id = i.id AND v.version = i.current_version
            WHERE i.seller_id = #{sellerId}
            ORDER BY i.created_at DESC, i.id DESC
            """)
    List<ItemViewRow> listMine(@Param("sellerId") String sellerId);

    @Select("""
            SELECT i.id, i.seller_id, a.public_ref AS seller_ref, a.nickname AS seller_name,
                   a.verified AS seller_verified, v.title, v.category, v.description, v.price, v.image_id,
                   v.version, v.review_status, v.review_reason, i.status, i.hidden, i.relist_allowed, i.created_at
            FROM items i
            JOIN accounts a ON a.id = i.seller_id
            JOIN item_versions v ON v.item_id = i.id AND v.version = i.current_version
            WHERE i.id = #{id}
            """)
    ItemViewRow findViewById(@Param("id") String id);
}
