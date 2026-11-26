package com.campus.trade.order;

import java.time.Instant;

/** 交付登记投影：登记事实，不等于完成。 */
public record DeliveryDto(String orderId, String actorId, String actorName, String note, Instant createdAt) {
}
