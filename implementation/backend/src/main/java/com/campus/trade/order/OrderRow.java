package com.campus.trade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** orders 行：阶段 + 成立时快照（快照只读，后续物品修改不回写）。 */
public class OrderRow {

    public String id;
    public String itemId;
    public int itemVersion;
    public String buyerId;
    public String sellerId;
    public String buyerPublicRef;
    public String sellerPublicRef;
    public String stage;
    public int disputed;
    public int reminded;
    public String title;
    public String description;
    public BigDecimal price;
    public String imageId;
    public String buyerName;
    public String sellerName;
    public String reason;
    public LocalDateTime createdAt;
    public LocalDateTime completedAt;
}
