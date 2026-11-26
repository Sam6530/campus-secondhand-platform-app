package com.campus.trade.order;

import java.time.LocalDateTime;

/** deliveries 行：交付登记事实，登记不等于成交。 */
public class DeliveryRow {

    public String id;
    public String orderId;
    public String actorId;
    public String note;
    public LocalDateTime createdAt;
}
