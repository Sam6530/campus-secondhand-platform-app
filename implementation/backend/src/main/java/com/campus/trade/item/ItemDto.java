package com.campus.trade.item;

import java.math.BigDecimal;
import java.time.Instant;

/** 本人/管理台可见的完整物品记录（含审核说明与治理标记）。 */
public record ItemDto(
        String id,
        String sellerId,
        String sellerName,
        String title,
        String category,
        String description,
        BigDecimal price,
        String imageId,
        int version,
        String status,
        int hidden,
        String reason,
        int relistAllowed,
        Instant createdAt) {
}
