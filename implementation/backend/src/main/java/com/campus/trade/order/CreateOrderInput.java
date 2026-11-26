package com.campus.trade.order;

/** 下单请求：物品与下单前看到的版本；买方身份从会话取得，不接受请求体指定。 */
public record CreateOrderInput(String itemId, Integer version) {
}
