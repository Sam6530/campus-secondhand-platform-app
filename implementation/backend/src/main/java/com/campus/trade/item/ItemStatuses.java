package com.campus.trade.item;

import java.util.List;

/** 物品状态与版本审核结论（第5.2节状态名，全文统一，不新增同义状态）。 */
public final class ItemStatuses {

    public static final String PENDING_REVIEW = "待审";
    public static final String ON_SALE = "在售";
    public static final String RETURNED = "审核退回";
    public static final String WITHDRAWN = "已撤下";
    public static final String IN_TRADE = "交易中";
    public static final String SOLD = "已成交";
    public static final String ABNORMAL_TERMINATED = "交易异常终止";

    public static final String REVIEW_PENDING = "待审";
    public static final String REVIEW_PASSED = "通过";
    public static final String REVIEW_RETURNED = "退回";

    public static final List<String> CATEGORIES =
            List.of("教材书籍", "数码配件", "宿舍生活", "运动户外", "其他");

    private ItemStatuses() {
    }
}
