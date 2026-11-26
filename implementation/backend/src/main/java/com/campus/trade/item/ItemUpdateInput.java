package com.campus.trade.item;

/** 修改请求（ItemUpdateInput）：在发布字段上增加必填 version，版本不匹配 400 拒绝。 */
public record ItemUpdateInput(
        String title,
        String category,
        String description,
        String price,
        String imageId,
        Integer version) {
}
