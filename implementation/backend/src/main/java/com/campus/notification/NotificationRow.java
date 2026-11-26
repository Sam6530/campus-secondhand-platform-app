package com.campus.notification;

import java.time.LocalDateTime;

/** notifications 行：已投递的站内通知。 */
public class NotificationRow {

    public String id;
    public String userId;
    public String outboxId;
    public String content;
    public LocalDateTime createdAt;
}
