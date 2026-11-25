package com.campus.trade.order;

import java.util.List;

/**
 * 当事人订单详情：进度、交付登记与评价。
 * V1 评价尚未开放（FR16 属 V2），reviews 返回空数组，不代表没有实现原需求。
 */
public record OrderDetailsDto(OrderDto order, DeliveryDto delivery, List<Object> reviews) {
}
