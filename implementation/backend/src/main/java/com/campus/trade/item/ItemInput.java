package com.campus.trade.item;

/** 发布请求（ItemInput）：价格以字符串传入，由服务端按 BR02 校验。 */
public record ItemInput(
        String title,
        String category,
        String description,
        String price,
        String imageId) {
}
