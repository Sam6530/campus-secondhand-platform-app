package com.campus.notification;

import java.time.Instant;

/** 通知投影：只含本人可见内容与对象时间，不含治理信息。 */
public record NotificationDto(String id, String userId, String content, Instant createdAt) {
}
