package com.campus.trade.order;

/** 订单阶段（第5.2节）：终态为已完成、已取消、已拒绝、已异常终止。 */
public final class OrderStages {

    public static final String WAIT_SELLER = "待卖方确认";
    public static final String WAIT_DELIVERY = "待交付";
    public static final String WAIT_BUYER = "待买方确认";
    public static final String COMPLETED = "已完成";
    public static final String CANCELLED = "已取消";
    public static final String REJECTED = "已拒绝";
    public static final String ABNORMAL_TERMINATED = "已异常终止";

    private OrderStages() {
    }
}
