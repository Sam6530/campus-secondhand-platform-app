package com.campus.notification;

import java.time.LocalDateTime;

/** notification_outbox 行：业务同事务写入，提交后投递；投递失败不回滚业务。 */
public class OutboxRow {

    public String id;
    public String eventKey;
    public String recipientId;
    public String eventType;
    public String content;
    public String status;
    public int attempts;
    public String lastError;
    public LocalDateTime nextAttemptAt;
    public LocalDateTime createdAt;
    public LocalDateTime deliveredAt;
}
