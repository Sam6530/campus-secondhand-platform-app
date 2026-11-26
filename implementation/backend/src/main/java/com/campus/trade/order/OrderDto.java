package com.campus.trade.order;

import java.math.BigDecimal;
import java.time.Instant;

/** 订单投影：快照字段 + 当前阶段；快照不随后续物品修改回写。 */
public record OrderDto(
        String id,
        String itemId,
        String buyerId,
        String sellerId,
        String title,
        String description,
        BigDecimal price,
        String imageId,
        String buyerName,
        String sellerName,
        int itemVersion,
        String stage,
        int disputed,
        int reminded,
        String reason,
        Instant createdAt,
        Instant completedAt) {
}
