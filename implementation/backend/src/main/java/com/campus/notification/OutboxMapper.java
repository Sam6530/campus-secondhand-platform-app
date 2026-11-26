package com.campus.notification;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxMapper {

    @Insert("""
            INSERT INTO notification_outbox (id, event_key, recipient_id, event_type, content, status, attempts,
                                             last_error, next_attempt_at, created_at, delivered_at)
            VALUES (#{id}, #{eventKey}, #{recipientId}, #{eventType}, #{content}, #{status}, #{attempts},
                    #{lastError}, #{nextAttemptAt}, #{createdAt}, #{deliveredAt})
            """)
    int insert(OutboxRow row);

    @Select("""
            SELECT id, event_key, recipient_id, event_type, content, status, attempts, last_error,
                   next_attempt_at, created_at, delivered_at
            FROM notification_outbox
            WHERE event_key = #{eventKey}
            """)
    OutboxRow findByEventKey(@Param("eventKey") String eventKey);

    /** 只补投指定收件人的待发送记录：读取通知的一方不替其他用户承担积压。 */
    @Select("""
            SELECT id, event_key, recipient_id, event_type, content, status, attempts, last_error,
                   next_attempt_at, created_at, delivered_at
            FROM notification_outbox
            WHERE recipient_id = #{recipientId}
              AND status IN ('PENDING', 'FAILED')
              AND next_attempt_at <= #{now}
              AND attempts < #{maxAttempts}
            ORDER BY created_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<OutboxRow> findRetryableForRecipient(@Param("recipientId") String recipientId,
                                              @Param("now") LocalDateTime now,
                                              @Param("maxAttempts") int maxAttempts,
                                              @Param("limit") int limit);

    @Update("""
            UPDATE notification_outbox
            SET status = 'SENT', delivered_at = #{deliveredAt}, last_error = NULL
            WHERE id = #{id}
            """)
    int markSent(@Param("id") String id, @Param("deliveredAt") LocalDateTime deliveredAt);

    @Update("""
            UPDATE notification_outbox
            SET status = 'FAILED', attempts = attempts + 1, last_error = #{lastError},
                next_attempt_at = #{nextAttemptAt}
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") String id,
                   @Param("lastError") String lastError,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    /**
     * 达到重试上限后转 DEAD：保留记录、停止自动重试并留下错误信息，便于人工核查后重置，
     * 不删除、不伪装成已发送。
     */
    @Update("""
            UPDATE notification_outbox
            SET status = 'DEAD', attempts = attempts + 1, last_error = #{lastError},
                next_attempt_at = #{now}
            WHERE id = #{id}
            """)
    int markDead(@Param("id") String id,
                 @Param("lastError") String lastError,
                 @Param("now") LocalDateTime now);
}
