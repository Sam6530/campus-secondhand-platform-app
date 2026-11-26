package com.campus.notification;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.campus.shared.db.Utc;

/**
 * EVT-RB-NOTICE：业务事务内写入待发送记录（与业务同提交），提交后由调度器投递。
 * Outbox 写失败属于业务未提交；提交后投递失败只影响通知，不撤销已成立业务。
 */
@Service
public class OutboxService {

    private final OutboxMapper outboxMapper;

    public OutboxService(OutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    public void append(String eventKey, String recipientId, String eventType, String content) {
        OutboxRow row = new OutboxRow();
        row.id = UUID.randomUUID().toString();
        row.eventKey = eventKey;
        row.recipientId = recipientId;
        row.eventType = eventType;
        row.content = clampContent(content);
        row.status = "PENDING";
        row.attempts = 0;
        row.nextAttemptAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        row.createdAt = Utc.now();
        outboxMapper.insert(row);
    }

    /** 按 UTF-16 安全截断，避免切断代理对（emoji 等）导致写库报错。 */
    private String clampContent(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= 500) {
            return content;
        }
        int end = 500;
        if (Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return content.substring(0, end);
    }
}
