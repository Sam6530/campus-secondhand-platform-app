package com.campus.trade.item;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 物品 + 当前版本 + 卖方的查询投影（公开与本人查询共用，输出前按场景裁剪）。 */
public class ItemViewRow {

    public String id;
    public String sellerId;
    public String sellerRef;
    public String sellerName;
    public int sellerVerified;
    public String title;
    public String category;
    public String description;
    public BigDecimal price;
    public String imageId;
    public int version;
    public String reviewStatus;
    public String reviewReason;
    public String status;
    public int hidden;
    public int relistAllowed;
    public LocalDateTime createdAt;
}
