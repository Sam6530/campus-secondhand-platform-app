package com.campus.notification;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campus.shared.account.AccountRow;
import com.campus.shared.account.ActorService;
import com.campus.shared.db.Utc;

/**
 * GET /api/notifications：先补投此前失败或未投递的通知，再返回本人最近 100 条。
 * 补投失败只影响通知，不改变已成立的交易结果。
 */
@RestController
public class NotificationController {

    private final ActorService actorService;
    private final OutboxDispatcher outboxDispatcher;
    private final NotificationMapper notificationMapper;

    public NotificationController(ActorService actorService, OutboxDispatcher outboxDispatcher,
                                  NotificationMapper notificationMapper) {
        this.actorService = actorService;
        this.outboxDispatcher = outboxDispatcher;
        this.notificationMapper = notificationMapper;
    }

    @GetMapping("/api/notifications")
    public List<NotificationDto> list() {
        AccountRow actor = actorService.requireAccount();
        outboxDispatcher.retryPending();
        return notificationMapper.listRecent(actor.id, 100).stream()
                .map(row -> new NotificationDto(row.id, actor.publicRef, row.content,
                        Utc.toInstant(row.createdAt)))
                .toList();
    }
}
