package com.campus.trade.item;

import java.time.LocalDateTime;

/** items 行：生命周期状态、当前版本指针与独立隐藏标记（隐藏不改状态）。 */
public class ItemRow {

    public String id;
    public String sellerId;
    public String status;
    public int currentVersion;
    public String imageId;
    public int hidden;
    public int relistAllowed;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
