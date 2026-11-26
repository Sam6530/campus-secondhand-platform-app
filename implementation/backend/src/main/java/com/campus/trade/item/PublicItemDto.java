package com.campus.trade.item;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 公开物品投影（GET /api/items）：不含审核说明与 hidden/relist_allowed；
 * title/description/seller_name 按 BR14 公开规则脱敏。
 */
public record PublicItemDto(
        String id,
        String sellerId,
        boolean sellerVerified,
        String sellerName,
        String title,
        String category,
        String description,
        BigDecimal price,
        String imageId,
        int version,
        String status,
        Instant createdAt) {
}
