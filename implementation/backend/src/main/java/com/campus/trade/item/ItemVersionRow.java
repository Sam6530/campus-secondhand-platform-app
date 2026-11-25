package com.campus.trade.item;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** item_versions 行：版本内容不可覆盖，审核结论只对当前版本有效。 */
public class ItemVersionRow {

    public String id;
    public String itemId;
    public int version;
    public String title;
    public String category;
    public String description;
    public BigDecimal price;
    public String imageId;
    public String reviewStatus;
    public String reviewReason;
    public String reviewedBy;
    public LocalDateTime reviewedAt;
    public LocalDateTime createdAt;
}
