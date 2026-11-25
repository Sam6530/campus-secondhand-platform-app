package com.campus.trade.order;

/**
 * 订单阶段操作请求：reason 为拒绝/交付/取消说明；declared 为当事人事实声明
 * （deliver=已交付，confirm=已收到，cancel=尚未实际交付）。
 */
public record OrderActionInput(String reason, Boolean declared) {
}
