package com.campus.notification;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotificationMapper {

    /** outbox_id 唯一：同一事件不会生成第二条最终通知（重试幂等）。 */
    @Insert("""
            INSERT IGNORE INTO notifications (id, user_id, outbox_id, content, created_at)
            VALUES (#{id}, #{userId}, #{outboxId}, #{content}, #{createdAt})
            """)
    int insertIgnore(NotificationRow row);

    @Select("""
            SELECT id, user_id, outbox_id, content, created_at
            FROM notifications
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<NotificationRow> listRecent(@Param("userId") String userId, @Param("limit") int limit);
}
