package com.campus.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.campus.shared.db.Utc;

/**
 * 提交后投递与失败补投：
 * - 业务已提交，投递失败只把 Outbox 标为 FAILED 并安排下次重试，不向调用方抛错；
 * - 每次读取通知前补投有上限批次，失败记录按时间轮转重试，不永久丢弃；
 * - 不提供任何通过 HTTP 触发的故障开关，故障验证在测试设施内注入。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxMapper outboxMapper;
    private final NotificationMapper notificationMapper;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int retryDelaySeconds;
    private final int maxAttempts;

    public OutboxDispatcher(OutboxMapper outboxMapper,
                            NotificationMapper notificationMapper,
                            TransactionTemplate transactionTemplate,
                            @Value("${campus.notification.batch-size:50}") int batchSize,
                            @Value("${campus.notification.retry-delay-seconds:30}") int retryDelaySeconds,
                            @Value("${campus.notification.max-attempts:100}") int maxAttempts) {
        this.outboxMapper = outboxMapper;
        this.notificationMapper = notificationMapper;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
        this.retryDelaySeconds = retryDelaySeconds;
        this.maxAttempts = maxAttempts;
    }

    /** 业务提交后调用：单个事件失败不影响其他事件，也不回滚业务。 */
    public void dispatch(List<String> eventKeys) {
        for (String eventKey : eventKeys) {
            dispatchOne(eventKey);
        }
    }

    /** 读取本人通知前的补投：只补投该收件人的待发送记录，不替其他用户承担积压。 */
    public void retryPendingFor(String recipientId) {
        List<OutboxRow> rows = outboxMapper.findRetryableForRecipient(recipientId, Utc.now(), maxAttempts,
                batchSize);
        for (OutboxRow row : rows) {
            dispatchOne(row.eventKey);
        }
    }

    private void dispatchOne(String eventKey) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                OutboxRow row = outboxMapper.findByEventKey(eventKey);
                if (row == null || "SENT".equals(row.status) || "DEAD".equals(row.status)) {
                    return;
                }
                if (notificationMapper.countByOutbox(row.id) > 0) {
                    // 已写过通知（例如上次写完但标记前失败），只补标记，不重复插入
                    outboxMapper.markSent(row.id, Utc.now());
                    return;
                }
                NotificationRow notification = new NotificationRow();
                notification.id = UUID.randomUUID().toString();
                notification.userId = row.recipientId;
                notification.outboxId = row.id;
                notification.content = row.content;
                notification.createdAt = Utc.now();
                notificationMapper.insert(notification);
                outboxMapper.markSent(row.id, Utc.now());
            });
        } catch (RuntimeException ex) {
            markFailedQuietly(eventKey, ex);
        }
    }

    private void markFailedQuietly(String eventKey, RuntimeException cause) {
        log.warn("通知投递失败，等待重试：eventKey={}，原因={}", eventKey, cause.getMessage());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                OutboxRow row = outboxMapper.findByEventKey(eventKey);
                if (row == null || "SENT".equals(row.status) || "DEAD".equals(row.status)) {
                    return;
                }
                if (row.attempts + 1 >= maxAttempts) {
                    outboxMapper.markDead(row.id, trim(cause.getMessage()), Utc.now());
                    log.error("通知投递达到上限 {} 次，已转 DEAD 等待人工核查（记录保留，不会被删除）：eventKey={}",
                            maxAttempts, eventKey);
                    return;
                }
                LocalDateTime nextAttempt = Utc.now().plusSeconds(retryDelaySeconds);
                outboxMapper.markFailed(row.id, trim(cause.getMessage()), nextAttempt);
            });
        } catch (RuntimeException ex) {
            log.error("待发送记录状态更新失败（保持原状态等待下次补投）：eventKey={}", eventKey, ex);
        }
    }

    private String trim(String message) {
        if (message == null) {
            return "投递失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
